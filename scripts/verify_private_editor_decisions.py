"""Deferred independent private footer geometry, identity continuity and complete edit replay."""
from pathlib import Path
import copy
import json
import math
import sys
import uuid

out = Path(sys.argv[1])
report = json.loads((out / 'private-editor-decision-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('heldRequestsScoped', 'sameRequestReflowStable', 'escapePreventsPublication',
            'curveEscapeRetainsEditor', 'cancelledSessionsClose', 'privateStageThenParentApply',
            'membershipOnlyCommit', 'oneUndoRedoPerEdit'):
    assert report[key] is True, key
assert report['callbacks'] == 2
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('Expression', 'Membership', 'Curve')
assert len(report['cases']) == 18
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, f) for i in range(6) for f in forms}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
rows = {'Expression': (('stage', 'cancel'), ('selectError',)),
        'Membership': (('apply', 'cancel', 'section'), ('select', 'clear', 'selected-only')),
        'Curve': (('apply',), ('cancel',))}
identities = {}


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
    boxes = case['actions']
    keys = {key for row in rows[form] for key in row}
    assert set(boxes) == set(case['actionIds']) == keys
    assert len(set(case['actionIds'].values())) == len(keys) and all(case['actionIds'].values())
    request = str(uuid.UUID(case['request']))
    identity = (request, case['actionIds'])
    if form not in identities:
        assert request not in {value[0] for value in identities.values()}
        identities[form] = identity
    assert identities[form] == identity
    previous_bottom = case['body']['y'] + case['body']['height']
    for row in rows[form]:
        for key in row:
            contains(case['modal'], boxes[key])
            assert boxes[key]['y'] >= previous_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
        previous_bottom = max(boxes[key]['y'] + boxes[key]['height'] for key in row)
names = ('before', 'expression', 'curve', 'membership')
projects = [json.loads((out / (name + '.json')).read_text(encoding='utf-8')) for name in names]
for i, name in enumerate(names[1:], 1):
    assert json.loads((out / (name + '-undone.json')).read_text(encoding='utf-8')) == projects[i-1]
    assert json.loads((out / (name + '-redone.json')).read_text(encoding='utf-8')) == projects[i]
before, expression, curve, membership = projects
expected = copy.deepcopy(before)
assert expected['noise2D'][0]['processing'][0]['parameters']['expression'] == 'x+1'
expected['noise2D'][0]['processing'][0]['parameters']['expression'] = 'x+2'
assert expression == expected
expected = copy.deepcopy(expression)
points = expected['noise2D'][1]['processing'][0]['parameters']['curve']['points']
assert [(p['x'], p['y']) for p in points] == [(0, 0), (.5, .25), (1, 1)]
del points[1]
assert curve == expected
expected = copy.deepcopy(curve)
assert expected['voxelSets'][0]['id'] == 'catalog'
assert expected['voxelSets'][0]['voxelTypeUuids'] == [expected['voxelTypes'][0]['uuid']]
expected['voxelSets'][0]['voxelTypeUuids'].append(expected['voxelTypes'][1]['uuid'])
assert membership == expected
for project in projects:
    assert project['formatVersion'] == 2 and 'packageVersion' not in project
    assert [n['id'] for n in project['noise2D']] == ['formula', 'shaped']
    assert [n['seedOffset'] for n in project['noise2D']] == [0, 0]
    assert not project['worldGenerator']['layers'] and not project['noise3D']
print('Private editor persistent footers, same-request identities and exact three edits/Undo/Redo replay PASS')
