"""Java-to-display integration, independent expectations, and replay input bounds."""
import copy
import json
from pathlib import Path
import unittest

from backend import HERE, JavaBackend, ReplayBackend
from model import apply_call, initial_display
from run import simulate
from scenario import loads, validate


class SimulatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.backend = JavaBackend()
        cls.scenarios = {p.stem: loads(p.read_text()) for p in (HERE / 'scenarios').glob('*.json')}

    @classmethod
    def tearDownClass(cls):
        cls.backend.close()

    def test_vehicle_problem_scenarios(self):
        for name, scenario in sorted(self.scenarios.items()):
            with self.subTest(name=name):
                result = simulate(self.backend, scenario)
                expected_failures = 2 if name.startswith('05-') else 0
                self.assertEqual(result['summary']['failed'], expected_failures)
                if expected_failures:
                    failures = [c for s in result['steps'] for c in s['checks'] if not c['passed']]
                    self.assertTrue(all(c['field'] == 'hudSecondary' and c['actual'] == '1.0 mi'
                                        and c['expected'] == '250 ft' for c in failures))

    def test_deterministic_fresh_processes(self):
        scenario = self.scenarios['01-close-turns']
        first = simulate(self.backend, scenario)
        simulate(self.backend, self.scenarios['03-lane-prefetch'])
        self.assertEqual(first, simulate(self.backend, scenario))

    def test_full_raw_frames_and_clear_order(self):
        result = simulate(self.backend, self.scenarios['01-close-turns'])
        first = result['steps'][4]
        frame = next(c['args'][0] for c in first['calls'] if c['method'] == 'turnListNew')
        self.assertEqual(frame[:5], [242, 2, 67, 70, 0])
        self.assertEqual(bytes(frame[5:]).decode('utf-16-le'), 'Cedar Avenue')
        self.assertEqual(next(c['args'] for c in first['calls'] if c['method'] == 'tbtSubInfo2C'),
                         [2, 0, 1, 3, 0, 250, 0, 0, 0])
        clear = result['steps'][-2]['calls']
        methods = [c['method'] for c in clear]
        self.assertLess(methods.index('destinationName'), methods.index('routeOwnership'))
        self.assertLess(methods.index('tbtSubInfo'), methods.index('routeOwnership'))
        self.assertTrue(any(c['method'] == 'tbtInfo' and c['args'][0] == 8 and c['args'][2] == 315 for c in clear))
        self.assertEqual(result['steps'][-1]['calls'], [])

    def test_lane_frames_are_not_trimmed(self):
        result = simulate(self.backend, self.scenarios['03-lane-prefetch'])
        frames = [c['args'][0] for s in result['steps'] for c in s['calls'] if c['method'] == 'laneInfo']
        self.assertTrue(all(len(f) == 81 for f in frames))
        selected = result['steps'][4]['display']['lanes']
        self.assertEqual([l['state'] for l in selected], [3, 1, 1, 3])
        self.assertEqual([l['icons'] for l in selected], [[7], [1], [1], [3]])

    def test_expectations_do_not_feed_the_display(self):
        scenario = copy.deepcopy(self.scenarios['01-close-turns'])
        original = simulate(self.backend, scenario)
        scenario['events'][4]['expect']['hudSecondary'] = '999 ft'
        altered = simulate(self.backend, scenario)
        self.assertEqual(altered['summary']['failed'], 1)
        self.assertEqual(original['steps'][4]['display'], altered['steps'][4]['display'])
        self.assertEqual(original['steps'][4]['calls'], altered['steps'][4]['calls'])

    def test_decoder_rejects_malformed_frames(self):
        for method, frame in [('turnListNew', [0xf2, 2, 67]), ('laneInfo', [0xf1, 9]),
                              ('turnListNew', [0xf1]), ('turnListNew', [0xf2, 1, 67, 0, 0, 1])]:
            with self.subTest(method=method, frame=frame), self.assertRaises(ValueError):
                apply_call(initial_display(), {'method': method, 'args': [frame]}, 0)

    def test_zero_distance_boundary_is_documented(self):
        state = initial_display()
        apply_call(state, {'method': 'tbtSubInfo2C', 'args': [0] * 9}, 0)
        self.assertIsNone(state['hud']['primary'])
        self.assertIsNone(state['hud']['secondary'])

    def test_bounds_and_unknown_fields(self):
        bad_events = [
            {'at': -1, 'kind': 'tick'}, {'at': 0.5, 'kind': 'tick'},
            {'at': 0, 'kind': 'route', 'distanceMeters': 30},
            {'at': 0, 'kind': 'route', 'lanesShown': 1},
            {'at': 0, 'kind': 'compass', 'heading': float('inf')},
            {'at': 0, 'kind': 'maneuver'},
            {'at': 0, 'kind': 'lanes', 'index': 1, 'lanes': [{}] * 9},
            {'at': 0, 'kind': 'route', 'selected': [65536]},
            {'at': 0, 'kind': 'route', 'destination': 'x' * 513},
            {'at': 0, 'kind': 'tick', 'expect': {'hudSecondaryMetres': 76}},
        ]
        for e in bad_events:
            with self.subTest(event=e), self.assertRaises(ValueError):
                validate({'schemaVersion': 1, 'events': [e]})
        with self.assertRaises(ValueError):
            validate({'schemaVersion': 1, 'events': [{'at': 1, 'kind': 'tick'}, {'at': 0, 'kind': 'tick'}]})
        validate({'schemaVersion': 1, 'events': [{'at': 0, 'kind': 'tick'}, {'at': 0, 'kind': 'tick'}]})

    def test_duplicate_json_keys_and_nonfinite_literals(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            loads('{"schemaVersion":1,"schemaVersion":1,"events":[{"at":0,"kind":"tick"}]}')
        with self.assertRaisesRegex(ValueError, 'Nonfinite'):
            loads('{"schemaVersion":1,"events":[{"at":0,"kind":"compass","heading":NaN}]}')

    def test_virtual_clock_refresh_requires_input(self):
        scenario = copy.deepcopy(self.scenarios['01-close-turns'])
        scenario['events'] = scenario['events'][:5] + [
            {'at': 2000, 'kind': 'route', 'distanceMetres': 1609},
            {'at': 4000, 'kind': 'tick'},
            {'at': 4100, 'kind': 'route', 'distanceMetres': 1609},
        ]
        result = simulate(self.backend, validate(scenario))
        methods = lambda i: [c['method'] for c in result['steps'][i]['calls']]
        self.assertNotIn('turnListNew', methods(5))
        self.assertEqual(methods(6), [])
        self.assertIn('turnListNew', methods(7))

    def test_renderer_accepts_another_backend(self):
        class AlternateBackend(ReplayBackend):
            def describe(self): return {'id': 'alternate', 'limitations': []}
            def replay(self, scenario):
                return {'schemaVersion': 1, 'backend': 'alternate', 'steps': [
                    {'at': 0, 'input': scenario['events'][0], 'bridge': {}, 'diagnostics': [],
                     'calls': [{'method': 'destinationName', 'args': [0, 'Independent backend']}]}
                ]}
        scenario = validate({'schemaVersion': 1, 'events': [{'at': 0, 'kind': 'tick',
                            'expect': {'destination': 'Independent backend'}}]})
        result = simulate(AlternateBackend(), scenario)
        self.assertEqual(result['summary']['passed'], 1)
        self.assertEqual(result['backendInfo']['id'], 'alternate')


if __name__ == '__main__':
    unittest.main()
