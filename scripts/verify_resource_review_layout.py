"""Deferred independent resource dialog geometry and members-only transaction replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'resource-review-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('cachedImportScroll', 'hiddenPagesDoNotReview', 'blockedImportPreserved',
            'cancelBeforeApply', 'filteredBulkSelection', 'rejectedMembershipRetained',
            'membershipOnlyRetry', 'oneUndoRedo', 'staleMembershipBlocked', 'privateInputPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified',
            'physicalClipboardVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('import-Resources', 'import-Filters', 'import-Issues', 'membership-Members', 'membership-Issues')
assert len(report['cases']) == 30
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, f) for i in range(6) for f in forms}
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
orders = {'import': (('apply', 'cancel', 'reload', 'validation'), ('resources', 'filters', 'issues')),
          'membership': (('apply', 'cancel', 'section'), ('select', 'clear', 'selected-only'))}
identities = {}


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i = case['scenario']
    kind = case['form'].split('-')[0]
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['work'], case['modal'])
    contains(case['modal'], case['body'])
    actions, rows = case['actions'], orders[kind]
    assert set(actions) == {key for row in rows for key in row}
    last_bottom = case['body']['y'] + case['body']['height']
    for row in rows:
        for key in row:
            contains(case['modal'], actions[key])
            assert actions[key]['y'] >= last_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(actions[left]['y'] - actions[right]['y']) <= .1
            assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .1
        last_bottom = max(actions[key]['y'] + actions[key]['height'] for key in row)
    if case['form'] in ('import-Resources', 'membership-Members'):
        contains(case['body'], case['list'])
        assert case['rows'] == 1 and len(case['ids']) == 1
        if kind not in identities:
            identities[kind] = case['ids']
        assert case['ids'] == identities[kind]
        line, frame, gap = (case['metrics'][k] for k in ('line', 'frame', 'gap'))
        assert line > 0 and frame > 0 and gap >= 0
        expected = math.ceil(3 * line + frame + 4 * gap if kind == 'import' else frame + gap)
        assert case['stride'] == expected
        assert case['rows'] <= math.ceil(case['list']['height'] / case['stride']) + 2
    else:
        assert case['rows'] == 0 and case['ids'] == {} and case['list'] is None
before = json.loads((out / 'membership-before.json').read_text(encoding='utf-8'))
after = json.loads((out / 'membership-after.json').read_text(encoding='utf-8'))
assert len(before['voxelTypes']) == 300 and len(before['voxelSets']) == 1
first, last = before['voxelTypes'][0]['uuid'], before['voxelTypes'][-1]['uuid']
assert before['voxelSets'][0]['id'] == after['voxelSets'][0]['id'] == 'catalog'
assert before['voxelSets'][0]['voxelTypeUuids'] == [first]
assert after['voxelSets'][0]['voxelTypeUuids'] == [first, last]
assert before['voxelTypes'][0]['id'] == 'type-00000' and before['voxelTypes'][-1]['id'] == 'type-00299'
assert [n['id'] for n in before['noise2D']] == ['editing', 'shared']
assert [n['algorithm']['parameters']['value'] for n in before['noise2D']] == [.125, .25]
# The one intentional membership change is the entire document delta.
replayed = json.loads(json.dumps(before))
replayed['voxelSets'][0]['voxelTypeUuids'].append(last)
assert after == replayed
print('Resource dialog persistent geometry, whole-pixel rows and exact members-only transaction replay PASS')
