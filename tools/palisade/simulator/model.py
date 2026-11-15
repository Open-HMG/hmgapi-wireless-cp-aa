"""Independent decoder of emitted display calls. Never reads bridge caches."""
import copy
import math

UNIT_NAMES = ['m', 'km', 'mi', 'ft', 'yd']
UNIT_METRES = [1, 1000, 1609, 1 / 3.28084, 1 / 1.09361]
EXPECT_KEYS = {'hudPrimary', 'hudSecondary', 'hudCount', 'laneCount', 'destination',
               'remaining', 'routeActive', 'compassHeading', 'clusterIcon', 'road',
               'hudPrimaryIcon', 'hudSecondaryIcon', 'clusterDistance'}


def distance(value, unit):
    if unit not in range(5) or not math.isfinite(value) or value < 0:
        raise ValueError('Invalid emitted distance')
    text = f'{value:.1f}' if unit in (1, 2) else f'{value:.0f}'
    return {'value': value, 'unit': UNIT_NAMES[unit], 'text': f'{text} {UNIT_NAMES[unit]}',
            'metres': value * UNIT_METRES[unit]}


def initial_display():
    return {'routeActive': False, 'destination': '', 'road': '', 'remaining': None, 'eta': None,
            'cluster': {'icon': None, 'distance': None, 'heading': None},
            'hud': {'count': 0, 'primaryIcon': None, 'secondaryIcon': None, 'road': '',
                    'primary': None, 'secondary': None}, 'lanes': [], 'ahead': [],
            'lastOutputAt': None, 'warnings': []}


def apply_call(state, call, at):
    method, a = call['method'], call['args']
    state['lastOutputAt'] = at
    if method == 'routeOwnership':
        state['routeActive'] = a[0]
    elif method == 'destinationName':
        state['destination'] = a[1]
    elif method == 'crossroadName':
        state['road'] = a[0]
    elif method == 'tbtInfo':
        if a[0] == 8:
            state['cluster'] = {'icon': 8, 'distance': None, 'heading': a[2]}
            state['ahead'] = []
        else:
            state['cluster'] = {'icon': a[0], 'distance': distance(a[3], a[6]) if a[4] == 0 else None,
                                'heading': None}
    elif method == 'tbtSubInfo':
        state['remaining'] = distance(a[0], a[2]) if a[1] == 0 else None
        state['eta'] = f'{a[3]:02d}:{a[4]:02d}' if a[5] == 1 else None
    elif method == 'tbtSubInfo2C':
        # An all-zero triplet is the clear sentinel in this model. Exact zero at
        # a maneuver boundary is indistinguishable at this API boundary.
        for key, offset in [('primary', 0), ('secondary', 3)]:
            unit, fraction, whole = a[offset:offset + 3]
            state['hud'][key] = distance(whole + fraction / 10, unit) if any(a[offset:offset + 3]) else None
    elif method == 'turnListNew':
        f = bytes(a[0])
        if not f or f[0] != 0xF2:
            raise ValueError('Invalid F2 frame')
        hud = state['hud']
        if len(f) == 1:
            hud.update(count=0, primaryIcon=None, secondaryIcon=None, road='')
        elif len(f) < 5 or f[1] not in (1, 2) or (len(f) - 5) % 2:
            raise ValueError('Malformed F2 frame')
        else:
            hud.update(count=f[1], primaryIcon=f[2], secondaryIcon=f[3] if f[1] == 2 else None,
                       road=f[5:].decode('utf-16-le', errors='replace').rstrip('\0'))
    elif method == 'laneInfo':
        f = a[0]
        if len(f) != 81 or f[0] != 0xF1 or not 0 <= f[1] <= 8:
            raise ValueError('Malformed F1 frame')
        state['lanes'] = [{'state': f[3 + k], 'icons': [x for x in f[19 + 3*k:22 + 3*k] if x]}
                          for k in range(f[1])]
    elif method == 'turnList':
        # Empty turnList calls clear explicitly; otherwise preserve exact output.
        state['ahead'] = [{**t, 'distance': distance(t['distance'], t['unit'])} for t in a[0]]
    else:
        state['warnings'].append('Unmodeled call: ' + method)


def observed(state):
    hud = state['hud']
    return {'hudPrimary': hud['primary']['text'] if hud['count'] and hud['primary'] else None,
            'hudSecondary': hud['secondary']['text'] if hud['count'] > 1 and hud['secondary'] else None,
            'hudCount': hud['count'], 'laneCount': len(state['lanes']),
            'destination': state['destination'], 'remaining': state['remaining']['text'] if state['remaining'] else None,
            'routeActive': state['routeActive'], 'compassHeading': state['cluster']['heading'],
            'clusterIcon': state['cluster']['icon'], 'road': hud['road'],
            'hudPrimaryIcon': hud['primaryIcon'], 'hudSecondaryIcon': hud['secondaryIcon'],
            'clusterDistance': state['cluster']['distance']['text'] if state['cluster']['distance'] else None}


def enrich(replay, scenario):
    state = initial_display()
    checks = []
    for index, step in enumerate(replay['steps']):
        for call in step['calls']:
            apply_call(state, call, step['at'])
        step['display'] = copy.deepcopy(state)
        got = observed(state)
        step['checks'] = []
        for key, expected in scenario['events'][index].get('expect', {}).items():
            actual = got[key]
            passed = actual == expected
            if key == 'compassHeading' and isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
                passed = abs(actual - expected) < .001
            check = {'step': index, 'field': key, 'expected': expected, 'actual': actual, 'passed': passed}
            step['checks'].append(check)
            checks.append(check)
    replay['scenario'] = scenario
    replay['summary'] = {'checks': len(checks), 'passed': sum(c['passed'] for c in checks),
                         'failed': sum(not c['passed'] for c in checks),
                         'events': len(replay['steps']), 'calls': sum(len(s['calls']) for s in replay['steps'])}
    replay['model'] = {'id': 'palisade-navigation-v1', 'fidelity': 'schematic',
                       'assumptions': [
                           'F2 and 2C are shown as independent icon and distance channels; physical pairing remains to be calibrated.',
                           'Loading is not inferred. Missing route context is shown explicitly.',
                           'Lane states 1/3 follow accepted stock encoding; preview colors and geometry are illustrative.',
                           'All-zero 2C triplets are shown as cleared; zero metres is ambiguous.',
                           'Displays retain the last emitted value until an explicit update; hardware timeouts are unknown.']}
    return replay
