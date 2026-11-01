"""Deferred independent initial-source creation, full transaction and native geometry replay."""
from copy import deepcopy
import json
import math
from pathlib import Path
import sys
from uuid import UUID

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('noise-creation-choice-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('optionalDefault', 'explicitDimensions', 'cancelBeforeCreate', 'preparedDefinitionRetained', 'oneUndoRedo'):
    assert report[key] is True, key
assert report['physicalDpiVerified'] is False
assert [(c['scenario'], c['dimension']) for c in report['cases']] == [(i, dimension) for i in range(6) for dimension in (2, 3)]


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i = case['scenario']
    assert case['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert case['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    contains(case['work'], case['modal']); contains(case['modal'], case['body'])
    actions = case['actions']
    assert set(actions) == {'primary', 'cancel'}
    for box in actions.values():
        contains(case['modal'], box)
        assert box['y'] >= case['body']['y'] + case['body']['height'] - .1
    assert actions['primary']['x'] + actions['primary']['width'] <= actions['cancel']['x'] + .1

empty, created, applied = read('empty.json'), read('created.json'), read('applied.json')
assert empty['formatVersion'] == 2 and not empty['noise2D'] and not empty['noise3D']
without_resources = deepcopy(created)
without_resources['noise2D'] = []; without_resources['noise3D'] = []
assert without_resources == empty
assert len(created['noise2D']) == 2 and len(created['noise3D']) == 1


def noise(value, name, dimension, algorithm, parameters, seed=0):
    assert set(value) == {'uuid', 'id', 'dimension', 'parentFolderUuid', 'algorithm', 'seedOffset',
                          'coordinateTransforms', 'processing', 'sharedParameters', 'pinnedControls', 'outputBinding'}
    assert str(UUID(value['uuid'])) == value['uuid']
    assert value['id'] == name and value['dimension'] == dimension and value['seedOffset'] == seed
    assert value['parentFolderUuid'] is None
    assert value['algorithm'] == dict(key=algorithm, parameters=parameters)
    assert all(not value[key] for key in ('coordinateTransforms', 'processing', 'sharedParameters', 'pinnedControls'))
    assert value['outputBinding'] == dict(sourceKind='Source Result', type='Scalar', ambientKey=None,
        value=None, parameterUuid=None, itemUuid=None, noiseGeneratorUuid=None, projection='XZ', slice=0)


noise(created['noise2D'][0], 'default-2', 2, 'CONSTANT', {'value': .125})
noise(created['noise2D'][1], 'gradient-2', 2, 'GRADIENT_Y', {})
noise(created['noise3D'][0], 'constant-3', 3, 'CONSTANT', {'value': -.25})
noise(applied['noise2D'][-1], 'frozen-2', 2, 'CONSTANT', {'value': -.75})
expected = deepcopy(created)
expected['noise2D'][0]['seedOffset'] = 17
expected['noise2D'].append(applied['noise2D'][-1])
assert applied == expected and read('redone.json') == applied and read('undone.json') == created
identities = [n['uuid'] for n in applied['noise2D'] + applied['noise3D']]
assert len(identities) == len(set(identities)) == 4
assert read('preferences-before.json') == read('preferences.json')
print('Optional/default/explicit source creation, twelve form receipts, prepared definitions and exact complete Apply/Undo/Redo PASS')
