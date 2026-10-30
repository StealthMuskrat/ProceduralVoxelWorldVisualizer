"""Deferred independent form geometry and exact complete-document transaction replay."""
from pathlib import Path
import copy
import json
import math
import sys
import uuid

out = Path(sys.argv[1])
report = json.loads((out / 'resource-forms-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('cancelBeforeMutation', 'heldRequestsScoped', 'staleOwnersBlocked',
            'rejectedCreateRetained', 'replacementFiltered', 'privateNoiseStage', 'oneUndoRedoPerEdit'):
    assert report[key] is True, key
assert report['callbacks'] == 1
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('Create', 'Placement', 'Delete', 'Resolution', 'Issues')
assert len(report['cases']) == 30
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, f) for i in range(6) for f in forms}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
requests = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i, form = case['scenario'], case['form']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['work'], case['modal'])
    contains(case['modal'], case['body'])
    noise = form in ('Resolution', 'Issues')
    rows = (('apply', 'cancel'), ('resolution', 'issues')) if noise else (('primary', 'cancel'),)
    boxes = case['actions']
    assert set(boxes) == {key for row in rows for key in row}
    previous_bottom = case['body']['y'] + case['body']['height']
    for row in rows:
        for key in row:
            contains(case['modal'], boxes[key])
            assert boxes[key]['y'] >= previous_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
        previous_bottom = max(boxes[key]['y'] + boxes[key]['height'] for key in row)
    request = str(uuid.UUID(case['request']))
    if form == 'Issues':
        assert requests[(i, 'Resolution')] == request
    else:
        assert request not in requests.values()
    requests[(i, form)] = request
names = ('before', 'created', 'placed', 'deleted', 'resolved')
projects = [json.loads((out / (name + '.json')).read_text(encoding='utf-8')) for name in names]
for i, name in enumerate(names[1:], 1):
    assert json.loads((out / (name + '-undone.json')).read_text(encoding='utf-8')) == projects[i-1]
    assert json.loads((out / (name + '-redone.json')).read_text(encoding='utf-8')) == projects[i]
before, created, placed, deleted, resolved = projects
assert len(before['noise2D']) == 257 and len(before['voxelGroups']) == 512
assert [v['id'] for v in before['voxelTypes']] == ['used', 'replacement']
expected = copy.deepcopy(before)
new_noise = created['noise2D'][-1]
assert new_noise['id'] == 'created-field' and new_noise['seedOffset'] == 0
assert not new_noise['processing'] and not new_noise['coordinateTransforms'] and not new_noise['sharedParameters']
assert new_noise['outputBinding']['sourceKind'] == 'Source Result'
assert new_noise['uuid'] not in {n['uuid'] for n in before['noise2D']}
expected['noise2D'].append(new_noise)
assert created == expected
expected = copy.deepcopy(created)
operation = placed['worldGenerator']['layers'][0]['operations'][0]
assert operation['noiseGeneratorUuid'] == before['noise2D'][1]['uuid']
assert operation['id'] == 'bulk-0000' and operation['interpretation'] == 'HEIGHT_BELOW'
assert operation['overrides'] == {} and operation['enabled'] is True
expected['worldGenerator']['layers'][0]['operations'].append(operation)
assert placed == expected
expected = copy.deepcopy(placed)
old, replacement = expected['voxelTypes']
expected['voxelTypes'] = [replacement]
for group in expected['voxelGroups']:
    assert group['voxelTypeUuids'] == [old['uuid']]
    group['voxelTypeUuids'] = [replacement['uuid']]
assert deleted == expected
expected = copy.deepcopy(deleted)
noise = expected['noise2D'][0]
assert noise['id'] == 'field' and noise['sharedParameters'][0]['id'] == 'gain'
assert noise['sharedParameters'][0]['value'] == 2
noise['sharedParameters'] = []
binding = noise['processing'][0]['inputs']['b']
binding['sourceKind'] = 'Constant'
binding['parameterUuid'] = None
binding['value'] = 2
assert resolved == expected
for project in projects:
    assert project['formatVersion'] == 2 and 'packageVersion' not in project
    assert project['noise2D'][0]['seedOffset'] == 0
    assert project['worldGenerator']['layers'][0]['defaults']['action'] == 'NO_CHANGE'
    assert not project['noise3D']
print('Resource forms geometry, scoped owners and exact four edits with Undo/Redo replay PASS')
