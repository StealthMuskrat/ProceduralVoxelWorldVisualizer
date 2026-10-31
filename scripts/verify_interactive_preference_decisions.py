"""Deferred independent Theme/Input footer geometry, identity and exact scoped JSON replay."""
from pathlib import Path
import copy
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'interactive-preference-decisions-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'heldSuccessorsBlocked', 'activeChangesScoped', 'previewPauseResume',
            'pausedOwnerChangesScoped', 'hiddenContextsReleased', 'recordingCancellationSuppressed', 'failedRetriesRetained',
            'independentDraftsPreserved', 'projectPreserved'):
    assert report[key] is True, key
assert report['themeErrors'] == 1
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert len(report['cases']) == 12
assert {(c['form'], c['scenario']) for c in report['cases']} == {
    (f, i) for f in ('Theme', 'Input') for i in range(6)}
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
keys = {'Theme': ('save', 'revert', 'reset', 'resume', 'view'),
        'Input': ('apply', 'revert', 'reset', 'show-problem', 'cancel-recording')}
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
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['window'], case['body'])
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == set(keys[form])
    assert len(set(case['ids'].values())) == 5 and all(case['ids'].values())
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
bindings = json.loads((out / 'input-saved.json').read_text(encoding='utf-8'))
theme = json.loads((out / 'theme-saved.json').read_text(encoding='utf-8'))
expected = copy.deepcopy(before)
expected['input']['shortcuts']['Profiler'] = 'Alt+F9'
assert bindings == expected
expected['appearance'].update(theme='Light', colors={}, statusColors={'Warning': [.25, .5, .75, 1]})
assert theme == expected
assert json.loads((out / 'user-config.json').read_text(encoding='utf-8')) == theme
assert theme['appearance']['windowScales']['extension-window'] == .75
assert theme['appearance']['windowScales']['Themes'] == 1
assert theme['workspace']['width'] == 1234
project = json.loads((out / 'unchanged-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['worldGenerator']['layers'] and not project['noise3D']
print('Interactive preference decisions, exact Theme/Input save merges and project replay PASS')
