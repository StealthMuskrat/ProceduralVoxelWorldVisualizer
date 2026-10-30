"""Deferred independent Properties footer geometry, owner continuity and exact parent replay."""
from pathlib import Path
import copy
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'properties-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('footerPersistent', 'sameOwnerReflowStable', 'escapePreventsMutation',
            'heldSuccessorsBlocked', 'revisionBlocked', 'privateEditorBlocked',
            'multiSelectionReadOnly', 'invalidSeedRetained', 'problemRevealAndFocus',
            'oneUndoRedo', 'revertPrivateOnly'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('Noise', 'Layer', 'Placement', 'Voxel', 'Membership', 'Folder', 'Multi', 'Empty')
assert len(report['cases']) == 48
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, f) for i in range(6) for f in forms}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
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
    assert case['editable'] is (form not in ('Multi', 'Empty'))
    contains(case['work'], case['window'])
    contains(case['window'], case['body'])
    keys = ('apply', 'revert', 'problems')
    boxes = case['actions']
    assert set(boxes) == set(case['actionIds']) == set(keys)
    assert len(set(case['actionIds'].values())) == 3 and all(case['actionIds'].values())
    for key in keys:
        contains(case['window'], boxes[key])
        assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
    for left, right in zip(keys, keys[1:]):
        assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
        assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    identity = (case['request'], case['actionIds'])
    assert case['request'] > 0
    if form not in identities:
        assert case['request'] not in {value[0] for value in identities.values()}
        identities[form] = identity
    assert identities[form] == identity
before, after, undone, redone = [json.loads((out / ('properties-' + name + '.json')).read_text(encoding='utf-8'))
                                  for name in ('before', 'after', 'undone', 'redone')]
assert before == undone and after == redone
expected = copy.deepcopy(before)
expected['noise2D'][0]['algorithm']['parameters']['value'] = .75
expected['noise2D'][0]['seedOffset'] = 17
assert after == expected
assert before['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert before['noise2D'][0]['seedOffset'] == 0
for project in (before, after, undone, redone):
    assert project['formatVersion'] == 2 and 'packageVersion' not in project
    assert [n['id'] for n in project['noise2D']] == ['a', 'b']
    assert len(project['noise2D'][0]['sharedParameters']) == 24
    assert project['worldGenerator']['layers'][0]['defaults']['action'] == 'NO_CHANGE'
    assert project['worldGenerator']['layers'][0]['operations'][0]['overrides'] == {}
print('Properties persistent footer, stable owner reflow and exact Apply/Revert/Undo/Redo replay PASS')
