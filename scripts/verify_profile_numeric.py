"""Deferred independent profile values, numeric geometry and complete persistence isolation."""
from copy import deepcopy
from pathlib import Path
import json
import math
import struct
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('profile-numeric-native.json')
assert report['formatVersion'] == 1 and report['physicalDpiVerified'] is False
assert all(report[k] is True for k in ('invalidRawGuarded', 'integerStored', 'ownerIdsReplaced', 'exactLongSliders'))
for value in (report['scalar'], report['vectorSlider']):
    assert math.isfinite(value) and -1e6 <= value <= 1e6
    assert value != round(value * 100000) / 100000
assert struct.pack('>d', float(report['scalarText'])) == struct.pack('>d', report['scalar'])
assert len(report['cases']) == 3
assert {c['scenario'] for c in report['cases']} == {0, 1, 2}
ids = None
presentations = set()
for case in report['cases']:
    assert case['scale'] == (1, 2.5, 6.25)[case['scenario']]
    outer, row = case['detail'], case['row']
    assert row['minimum'] == -10000 and row['maximum'] == 10000
    for b in (outer, row['slider'], row['input']):
        assert all(math.isfinite(v) for v in b.values()) and b['width'] > 0 and b['height'] > 0
    for b in (row['slider'], row['input']):
        assert b['x'] >= outer['x'] - 1.1
        assert b['x'] + b['width'] <= outer['x'] + outer['width'] + 1.1
    pair = (row['sliderId'], row['inputId'])
    assert all(pair) and pair[0] != pair[1]
    if ids is None:
        ids = pair
    assert ids == pair
    presentations.add(row['inline'])
    if row['inline']:
        assert abs(row['slider']['y'] - row['input']['y']) <= 1.1
        assert row['slider']['x'] + row['slider']['width'] <= row['input']['x'] + 1.1
    else:
        assert row['slider']['y'] + row['slider']['height'] <= row['input']['y'] + 1.1
assert presentations == {True, False}
profile = read('app-config.json')
expected = deepcopy(read('profile-before.json'))
expected['controls']['noise2D.algorithm.CONSTANT.value']['order'] = -17
expected['creationDefaults'].update({
    'noise2D.algorithm.CONSTANT.value': report['scalar'],
    'processing.DOT.input.a': [3.5, 2.25, 9],
    'processing.FBM.parameter.octaves': 7})
assert profile == expected
assert type(profile['creationDefaults']['processing.FBM.parameter.octaves']) is int
assert read('before.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
created = read('created.json')
assert len(created['noise2D']) == len(created['noise3D']) == 1
flat, dot = created['noise2D'][0], created['noise3D'][0]
assert flat['id'] == 'future-flat' and flat['algorithm'] == {
    'key': 'CONSTANT', 'parameters': {'value': report['scalar']}}
assert dot['id'] == 'future-dot'
item, = dot['processing']
assert item['kind'] == 'DOT' and item['inputs']['a']['value'] == [3.5, 2.25, 9]
assert item['inputs']['b']['value'] == [1, 2, 3]
assert dot['outputBinding']['itemUuid'] == item['uuid']
assert 3.5 * 1 + 2.25 * 2 + 9 * 3 == 35
print('Profile slider exact raw values, order reflow, owner identities and whole profile/project/preference isolation PASS')
