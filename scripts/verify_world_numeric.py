"""Deferred independent World numeric layout and complete sparse inheritance/Undo replay."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('world-numeric-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-font-fixture'
assert report['physicalDpiVerified'] is False
for key in ('integerTextRestored', 'sparseInheritance', 'resetUndoRedo'):
    assert report[key] is True
guidance = report['guidance']
paths = ('settings/threshold', 'settings/minY', 'settings/samplingTransforms/0/x',
         'settings/parameterDrivers/0/inputMin', 'settings/parameterDrivers/0/transformIndex',
         'settings/typeIntervals/0/min', 'settings/fieldGates/threshold', 'settings/seedOffset')
assert len(guidance) == 19
assert {(g['path'], g['part']) for g in guidance} == (
    {(path, part) for path in paths for part in ('slider', 'text')} |
    {(path, 'control') for path in ('inherited/threshold', 'override/threshold', 'reset/threshold')})
for g in guidance:
    if g['path'].startswith('settings/'):
        assert g['topic'] == 'world.setting.' + g['path'].split('/')[1]
    elif g['path'].startswith('inherited/'):
        assert g['topic'] == 'world.setting.threshold'
    else:
        assert g['topic'] == 'world.inheritance'
for path in paths:
    text = (out / ('world-help-' + path[9:].replace('/', '-') + '.txt')).read_text(encoding='utf-8')
    assert 'World fixture 100% ### snow 雪 / ' + path in text
    assert 'Topic: world.setting.' + path.split('/')[1] in text
v = report['layerSlider']
assert math.isfinite(v) and -1 <= v <= 1 and v != round(v * 100000) / 100000
assert -1 <= report['overrideSlider'] <= 1
cases = report['cases']
assert len(cases) == 18
assert {(c['phase'], c['scenario']) for c in cases} == {
    (p, i) for p in ('Layer', 'Inherited', 'Override') for i in range(6)}
identities = {}
presentations = set()


def rectangle(b):
    assert all(math.isfinite(v) for v in b.values())
    assert b['width'] > 0 and b['height'] > 0


for c in cases:
    assert c['scale'] == (1, .5, 1, 2.5, 6.25, 1)[c['scenario']]
    body = c['body']
    rectangle(body)
    if c['phase'] == 'Inherited':
        assert c['rows'] == []
    elif c['phase'] == 'Override':
        assert len(c['rows']) == 1
    else:
        # Scalar defaults, two integer bounds and every expanded transform/driver/gate/interval row.
        assert len(c['rows']) >= 40
    ids = []
    for row in c['rows']:
        slider, text = row['slider'], row['input']
        assert row['minimum'] <= row['maximum']
        for b in (slider, text):
            rectangle(b)
            assert b['x'] >= body['x'] - 1.1
            assert b['x'] + b['width'] <= body['x'] + body['width'] + 1.1
        presentations.add(row['inline'])
        if row['inline']:
            assert abs(slider['y'] - text['y']) <= 1.1
            assert slider['x'] + slider['width'] <= text['x'] + 1.1
        else:
            assert abs(slider['x'] - text['x']) <= 1.1
            assert slider['y'] + slider['height'] <= text['y'] + 1.1
        ids.extend((row['inputId'], row['sliderId']))
    assert len(ids) == len(set(ids)) and all(ids)
    if c['phase'] not in identities:
        identities[c['phase']] = set(ids)
    assert identities[c['phase']] == set(ids)
assert presentations == {True, False}

before = read('before.json')
assert before['project']['minY'] == -8 and before['project']['maxY'] == 8
noise, = before['noise2D']
voxel, = before['voxelTypes']
layer, = before['worldGenerator']['layers']
operation, = layer['operations']
assert noise['algorithm'] == {'key': 'CONSTANT', 'parameters': {'value': 1}}
assert layer['defaults']['threshold'] == .5 and layer['defaults']['action'] == 'SET'
assert layer['defaults']['voxelTypeUuid'] == voxel['uuid']
assert layer['defaults']['minY'] == -2147483648 and layer['defaults']['maxY'] == 2147483647
assert [t['kind'] for t in layer['defaults']['samplingTransforms']] == ['TRANSLATE', 'SCALE', 'ROTATE']
assert len(layer['defaults']['typeIntervals']) == len(layer['defaults']['parameterDrivers']) == 1
assert layer['defaults']['parameterDrivers'][0]['enabled'] is False
assert layer['defaults']['fieldGates']['kind'] == 'COMPARE'
assert operation['interpretation'] == 'SELECTION' and operation['noiseGeneratorUuid'] == noise['uuid']
assert operation['overrides'] == {}
layer_expected = deepcopy(before)
layer_expected['worldGenerator']['layers'][0]['defaults']['threshold'] = 2
override_expected = deepcopy(layer_expected)
override_expected['worldGenerator']['layers'][0]['operations'][0]['overrides'] = {'threshold': 0}
live_expected = deepcopy(before)
live_expected['worldGenerator']['layers'][0]['defaults']['threshold'] = -.5
for name in ('layer', 'override-undone', 'reset', 'reset-redone'):
    assert read(name + '.json') == layer_expected, name
for name in ('override', 'override-redone', 'reset-undone'):
    assert read(name + '.json') == override_expected, name
assert read('live.json') == live_expected
assert read('preferences-before.json') == read('preferences.json')
print('World numeric paired layouts, inherited read-only values, exact zero/reset/live inheritance and whole-state replay PASS')
