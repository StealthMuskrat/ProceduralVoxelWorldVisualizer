"""Deferred independent immediate-settings geometry, callbacks and exact active-only save replay."""
from pathlib import Path
import copy
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'immediate-settings-decisions-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-user-scale'
for key in ('escapePreserved', 'hiddenContextsReleased', 'instanceScopesDistinct',
            'callbackAvailabilityScoped', 'disabledRetryBlocked', 'immediateEditsPreserved',
            'failedRetryRetained', 'privateFormsPreserved', 'projectPreserved'):
    assert report[key] is True, key
assert report['saveAttempts'] == 5 and report['saves'] == 4 and report['saveErrors'] == 1
assert report['restores'] == 2 and report['centers'] == report['changes'] == 1
assert report['limitOpens'] == report['retries'] == 1
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
keys = {'Workspace': ('defaults', 'center', 'save-preferences'),
        'Workspace Basic': ('defaults', 'save-preferences'),
        'Editor': ('save',), 'Visualization': ('save', 'limits', 'retry')}
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
    assert case['request'] > 0
    identity = (case['request'], case['ids'])
    if form not in identities:
        identities[form] = identity
    assert identities[form] == identity
assert identities['Workspace'][1]['defaults'] != identities['Workspace Basic'][1]['defaults']
before = json.loads((out / 'preferences-before.json').read_text(encoding='utf-8'))
expected = copy.deepcopy(before)
expected['workspace']['visibleWindows'] = ['Logs', 'Help']
expected['editor'].update(autoRegenerate=False, opportunitiesPerSecond=20)
expected['visualization']['grid'] = False
for name in ('editor-saved.json', 'workspace-saved.json', 'visualization-saved.json', 'preferences.json'):
    assert json.loads((out / name).read_text(encoding='utf-8')) == expected
assert expected['appearance']['globalScale'] == 1
assert 'Resources' not in expected['appearance']['windowScales']
assert expected['appearance']['windowScales']['extension-window'] == .75
assert expected['performance']['cacheMiB'] != 17
assert expected['performance']['previewCellBudget'] != 2**63 - 1
project = json.loads((out / 'unchanged-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['noise3D'] and not project['worldGenerator']['layers']
print('Immediate settings footer geometry, callback ownership and complete active-only preference replay PASS')
