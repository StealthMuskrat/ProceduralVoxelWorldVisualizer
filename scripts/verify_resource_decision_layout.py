"""Deferred independent draft decision geometry and complete committed edit replay."""
from pathlib import Path
import copy
import json
import math
import sys
import uuid

out = Path(sys.argv[1])
report = json.loads((out / 'resource-decision-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('rejectedApplyRetained', 'cancelBeforeMutation', 'heldRequestScoped',
            'staleOwnersBlocked', 'combinedEditOnce', 'refreshFailureConsumed', 'oneUndoRedo'):
    assert report[key] is True, key
assert report['callbacks'] == 1
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('Selection', 'Edit', 'Source')
assert len(report['cases']) == 18
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, f) for i in range(6) for f in forms}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
versions = dict.fromkeys(forms, 0)


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
    keys = ('APPLY', 'DISCARD', 'CANCEL')
    boxes = case['buttons']
    assert set(boxes) == set(keys)
    for key in keys:
        contains(case['modal'], boxes[key])
        assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
    for left, right in zip(keys, keys[1:]):
        assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
        assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    assert case['request'] > versions[form]
    versions[form] = case['request']
projects = [json.loads((out / ('resource-' + name + '.json')).read_text(encoding='utf-8'))
            for name in ('before', 'after', 'undone', 'redone')]
before, after, undone, redone = projects
assert before == undone and after == redone
expected = copy.deepcopy(before)
expected['noise2D'][0]['seedOffset'] = 17
expected['noise2D'][1]['id'] = 'renamed-b'
assert after == expected
identities = [n['uuid'] for n in before['noise2D']]
assert len(set(identities)) == 3
for value in identities:
    assert str(uuid.UUID(value)) == value
for project in projects:
    assert project['formatVersion'] == 2 and 'packageVersion' not in project
    assert [n['uuid'] for n in project['noise2D']] == identities
    assert not project['noise3D'] and not project['worldGenerator']['layers']
    for noise, value in zip(project['noise2D'], (.125, .25, .5), strict=True):
        assert noise['algorithm']['key'] == 'CONSTANT'
        assert noise['algorithm']['parameters'] == {'value': value}
assert [n['id'] for n in before['noise2D']] == ['a', 'b', 'c']
assert [n['seedOffset'] for n in before['noise2D']] == [0, 0, 0]
print('Resource decision geometry, ownership and exact combined edit/Undo/Redo replay PASS')
