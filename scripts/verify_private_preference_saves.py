"""Deferred independent private-form footer identities and exact ordinary-save exclusion replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'private-preference-saves-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-user-scale'
for key in ('escapePreserved', 'hiddenCallbacksScoped', 'resetCallbacksScoped',
            'hiddenPrivateFootersScoped', 'availabilityScoped', 'recordingSuppressed',
            'failedRetriesRetained', 'activeOnlySaved', 'projectPreserved'):
    assert report[key] is True, key
assert report['attempts'] == 9 and report['saves'] == 5 and report['errors'] == 4
assert report['openedThemes'] == 1
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
keys = {'Appearance': ('apply', 'revert', 'invalid'), 'Limits': ('apply', 'revert', 'problem'),
        'Trust': ('apply', 'revert', 'fields'),
        'Input': ('apply', 'revert', 'reset', 'show-problem', 'cancel-recording')}
assert len(report['cases']) == 24
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, i) for f in keys for i in range(6)}
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
    contains(case['window'], case['body'])
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == set(keys[form])
    assert len(set(case['ids'].values())) == len(boxes) and all(case['ids'].values())
    for key in keys[form]:
        contains(case['window'], boxes[key])
        assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
    for left, right in zip(keys[form], keys[form][1:]):
        assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
        assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    callbacks = case['callbacks']
    assert set(callbacks) == ({'themes', 'save'} if form == 'Appearance' else {'save'})
    assert len(set(callbacks.values())) == len(callbacks) and all(callbacks.values())
    identity = (case['ids'], callbacks)
    if form not in identities:
        identities[form] = identity
    assert identities[form] == identity
baseline = json.loads((out / 'preferences-before.json').read_text(encoding='utf-8'))
for name in ('appearance-saved.json', 'limits-saved.json', 'trust-saved.json', 'input-saved.json', 'preferences.json'):
    assert json.loads((out / name).read_text(encoding='utf-8')) == baseline
assert baseline['appearance']['globalScale'] == 1 and 'Resources' not in baseline['appearance']['windowScales']
assert baseline['appearance']['windowScales']['extension-window'] == .75
assert baseline['performance']['cacheMiB'] != 17 and baseline['performance']['previewCellBudget'] != 2**63 - 1
assert baseline['performance']['updateEndpoint'] == '' and baseline['performance']['trustedOrigins'] == []
assert baseline['input']['shortcuts']['Profiler'] == 'Ctrl+Shift+P'
project = json.loads((out / 'unchanged-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['noise3D'] and not project['worldGenerator']['layers']
print('Private ordinary-save callback scopes, exact active-only preferences and unchanged project replay PASS')
