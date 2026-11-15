"""Strict, bounded input contract shared by built-in scenarios and JSON imports."""
import json
import math
from model import EXPECT_KEYS

COMMON = {'at', 'kind', 'label', 'expect', 'truth'}
FIELDS = {
    'route': {'component', 'state', 'destination', 'etaEpoch', 'remainingMetres', 'remainingText', 'remainingUnit',
              'distanceMetres', 'distanceText', 'distanceUnit', 'selected', 'visible', 'laneIndex', 'lanesShown', 'source'},
    'maneuver': {'component', 'index', 'type', 'road', 'distanceMetres', 'distanceText', 'distanceUnit', 'side', 'junction', 'exit'},
    'lanes': {'component', 'index', 'lanes'}, 'compass': {'heading'}, 'tick': set(), 'stop': set(), 'disconnect': set(),
}
TEXT = {'destination', 'road', 'distanceText', 'remainingText', 'source', 'label'}
BOOL = {'visible', 'lanesShown'}


def bounded_int(value, low, high, name):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f'{name} must be an integer from {low} to {high}')


def validate(s):
    if not isinstance(s, dict) or s.get('schemaVersion') != 1 or type(s.get('schemaVersion')) is not int:
        raise ValueError('scenario.schemaVersion must be 1')
    unknown = set(s) - {'schemaVersion', 'id', 'title', 'description', 'units', 'events', 'provenance'}
    if unknown:
        raise ValueError('Unknown scenario fields: ' + ', '.join(sorted(unknown)))
    for key in ('id', 'title', 'description', 'provenance'):
        if key in s and (not isinstance(s[key], str) or len(s[key]) > 4000):
            raise ValueError(f'{key} must be text of at most 4000 characters')
    if s.get('units', 'mi') not in ('mi', 'km'):
        raise ValueError('units must be mi or km')
    events = s.get('events')
    if not isinstance(events, list) or not 1 <= len(events) <= 2000:
        raise ValueError('events must contain 1 to 2000 entries')
    previous = -1
    for index, e in enumerate(events):
        prefix = f'event {index + 1}'
        if not isinstance(e, dict) or e.get('kind') not in FIELDS:
            raise ValueError(prefix + ': unknown event kind')
        unknown = set(e) - COMMON - FIELDS[e['kind']]
        if unknown:
            raise ValueError(prefix + ': unknown fields ' + ', '.join(sorted(unknown)))
        bounded_int(e.get('at'), 0, 86400000, prefix + '.at')
        if e['at'] < previous:
            raise ValueError(prefix + ': timestamps must be nondecreasing')
        previous = e['at']
        for key, value in e.items():
            name = prefix + '.' + key
            if key in TEXT:
                if not isinstance(value, str) or len(value) > 512:
                    raise ValueError(name + ' must be text of at most 512 characters')
                try:
                    value.encode('utf-8')
                except UnicodeError as exc:
                    raise ValueError(name + ' contains an unpaired surrogate') from exc
            elif key in BOOL:
                if type(value) is not bool:
                    raise ValueError(name + ' must be boolean')
            elif key in FIELDS[e['kind']] - TEXT - BOOL - {'selected', 'lanes', 'heading'}:
                low, high = (-1, 2147483647) if key.endswith('Metres') else (0, 65535)
                if key == 'exit': low, high = -360, 360
                if key == 'etaEpoch': low, high = 500000000, 4102444800
                if key in ('remainingUnit', 'distanceUnit'): low, high = 0, 4
                if key == 'state': low, high = 0, 6
                if key in ('side', 'junction'): low, high = 0, 1
                bounded_int(value, low, high, name)
        if e['kind'] in ('maneuver', 'lanes') and 'index' not in e:
            raise ValueError(prefix + ': index required')
        if 'selected' in e:
            if not isinstance(e['selected'], list) or len(e['selected']) > 32:
                raise ValueError(prefix + ': selected must contain up to 32 indices')
            for v in e['selected']: bounded_int(v, 0, 65535, prefix + '.selected[]')
        if e['kind'] == 'compass':
            h = e.get('heading')
            if type(h) not in (int, float) or not math.isfinite(h) or not 0 <= h <= 360:
                raise ValueError(prefix + ': heading must be finite and from 0 to 360')
        if e['kind'] == 'lanes':
            lanes = e.get('lanes')
            if not isinstance(lanes, list) or len(lanes) > 8:
                raise ValueError(prefix + ': lanes must contain up to 8 entries')
            for lane in lanes:
                if not isinstance(lane, dict) or set(lane) - {'status', 'angles', 'preferred'}:
                    raise ValueError(prefix + ': invalid lane fields')
                if 'status' in lane: bounded_int(lane['status'], 0, 2, prefix + '.lane.status')
                for key in ('angles', 'preferred'):
                    if key in lane:
                        if not isinstance(lane[key], list) or len(lane[key]) > 16:
                            raise ValueError(prefix + ': lane angles must contain up to 16 entries')
                        for a in lane[key]: bounded_int(a, -360, 360, prefix + '.lane.' + key)
        expect = e.get('expect', {})
        if not isinstance(expect, dict) or set(expect) - EXPECT_KEYS:
            raise ValueError(prefix + ': unsupported expectation')
        for key, value in expect.items():
            if key == 'routeActive':
                if type(value) is not bool: raise ValueError(prefix + ': routeActive expectation must be boolean')
            elif key in {'hudCount', 'laneCount', 'clusterIcon', 'hudPrimaryIcon', 'hudSecondaryIcon'}:
                if value is not None: bounded_int(value, 0, 255, prefix + '.expect.' + key)
            elif key == 'compassHeading':
                if value is not None and (type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 360):
                    raise ValueError(prefix + ': invalid compass expectation')
            elif value is not None and (not isinstance(value, str) or len(value) > 512):
                raise ValueError(prefix + ': display expectations must be text or null')
        if 'truth' in e and (not isinstance(e['truth'], str) or len(e['truth']) > 2000):
            raise ValueError(prefix + ': truth must be a note of at most 2000 characters')
    return s


def loads(text):
    def pairs(items):
        out = {}
        for key, value in items:
            if key in out: raise ValueError('Duplicate JSON key: ' + key)
            out[key] = value
        return out
    def invalid(value):
        raise ValueError('Nonfinite JSON number: ' + value)
    try:
        return validate(json.loads(text, object_pairs_hook=pairs, parse_constant=invalid))
    except RecursionError as exc:
        raise ValueError('JSON nesting is too deep') from exc
