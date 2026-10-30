"""Deferred independent project/profile footer geometry and distinct publication replay."""
from pathlib import Path
import copy
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'settings-decisions-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('projectCancelBeforeMutation', 'projectReloadEscape', 'heldSuccessorsBlocked',
            'profileEscapePreserved', 'profileRetryRetained', 'futureItemsOnly', 'feedbackFocus',
            'privateOwnersPreserved', 'oneProjectUndoRedo'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert len(report['cases']) == 12
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, i) for f in ('Project', 'Application') for i in range(6)}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
keys = {'Project': ('apply', 'cancel', 'reload', 'problem'),
        'Application': ('apply', 'revert', 'builtins', 'invalid-value')}
identities = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    form, i = case['form'], case['scenario']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['window'], case['body'])
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == set(keys[form])
    assert len(set(case['ids'].values())) == 4 and all(case['ids'].values())
    for key in keys[form]:
        contains(case['window'], boxes[key])
        assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
    for left, right in zip(keys[form], keys[form][1:]):
        assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
        assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    assert case['request'] > 0
    identity = (case['request'], case['ids'])
    if form not in identities:
        identities[form] = identity
    assert identities[form] == identity
before, after, undone, redone = [json.loads((out / ('settings-' + name + '.json')).read_text(encoding='utf-8'))
                                  for name in ('before', 'after', 'undone', 'redone')]
assert before == undone and after == redone
expected = copy.deepcopy(before)
expected['project'].update(seed=-(2**63), minY=-2, maxY=3)
assert after == expected
for project in (before, after, undone, redone):
    assert project['formatVersion'] == 2
    assert len(project['noise2D']) == 1 and project['noise2D'][0]['id'] == 'existing'
    assert project['noise2D'][0]['seedOffset'] == 0
    assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
    assert not project['noise3D'] and not project['worldGenerator']['layers']
profile_before = json.loads((out / 'profile-before.json').read_text(encoding='utf-8'))
profile_after = json.loads((out / 'profile-after.json').read_text(encoding='utf-8'))
expected = copy.deepcopy(profile_before)
expected['creationDefaults'].update({'noise2D.algorithm': 'CONSTANT', 'noise2D.algorithm.CONSTANT.value': .625})
assert profile_after == expected and profile_after['formatVersion'] == 1
assert json.loads((out / 'app-config.json').read_text(encoding='utf-8')) == profile_after
future = json.loads((out / 'future-profile-item.json').read_text(encoding='utf-8'))
assert future['formatVersion'] == 2 and len(future['noise2D']) == 1
noise = future['noise2D'][0]
assert noise['id'] == 'future' and noise['seedOffset'] == 0 and noise['dimension'] == 2
assert noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': .625}
assert not noise['processing'] and not noise['coordinateTransforms'] and not noise['sharedParameters']
assert not (out / 'user-config.json').exists()
print('Settings footer ownership, exact project transaction and separate future-only application profile replay PASS')
