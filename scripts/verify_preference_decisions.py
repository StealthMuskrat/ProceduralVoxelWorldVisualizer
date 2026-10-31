"""Deferred independent preference footer identities and exact owned-section save replay."""
from pathlib import Path
import copy
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'preference-decisions-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-user-scale'
for key in ('escapePreserved', 'heldSuccessorsBlocked', 'activeChangesScoped', 'revertPrivateOnly',
            'failedRetriesRetained', 'independentDraftsPreserved', 'projectPreserved'):
    assert report[key] is True, key
assert report['errors'] == 3
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('Limits', 'Trust', 'Scales')
assert len(report['cases']) == 18
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, i) for f in forms for i in range(6)}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
keys = {'Limits': ('apply', 'revert', 'problem'), 'Trust': ('apply', 'revert', 'fields'),
        'Scales': ('apply', 'revert', 'invalid')}
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
    assert len(set(case['ids'].values())) == 3 and all(case['ids'].values())
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
before = json.loads((out / 'preferences-before.json').read_text(encoding='utf-8'))
limits = json.loads((out / 'limits-saved.json').read_text(encoding='utf-8'))
trust = json.loads((out / 'trust-saved.json').read_text(encoding='utf-8'))
scales = json.loads((out / 'scales-saved.json').read_text(encoding='utf-8'))
expected = copy.deepcopy(before)
expected['performance'].update(cacheMiB=0, previewCellBudget=2**63-1)
assert limits == expected
expected['performance'].update(updateEndpoint='https://updates.example.test/meta.json',
                               trustedOrigins=['https://updates.example.test', 'https://releases.example.test'])
assert trust == expected
expected['appearance']['globalScale'] = 1.25
expected['appearance']['windowScales']['Resources'] = 1.5
assert scales == expected
assert json.loads((out / 'preferences.json').read_text(encoding='utf-8')) == scales
assert scales['appearance']['theme'] == 'Light' and scales['workspace']['width'] == 1234
assert scales['appearance']['windowScales']['extension-window'] == .75
project = json.loads((out / 'unchanged-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['worldGenerator']['layers'] and not project['noise3D']
print('Preference action identities, scoped exact save merges and independent project replay PASS')
