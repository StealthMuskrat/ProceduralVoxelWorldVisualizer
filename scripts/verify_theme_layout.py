"""Deferred independent Themes geometry, RGBA/stride and private save/recovery receipt replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'theme-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('safeInspector', 'ordinarySaveExcludedDraft', 'externalPauseResume', 'writeRetry',
            'projectPreserved', 'closeRestoredSavedStyle'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
registry = json.loads((root / 'registries/theme-palette.json').read_text(encoding='utf-8'))
keys = {color['name']: str(color['key']) for color in registry['colors']}
assert len(report['cases']) == 6
assert {case['scenario'] for case in report['cases']} == set(range(6))
sizes = ((1400, 1000), (700, 640), (320, 640), (260, 640), (720, 640), (1000, 300))
globals_ = (1, 1, 1.5, 2.5, 2.5, 1)
windows = (1, 1, 1, 1, 2.5, 1)
ids = None
columns = set()
pane_modes = set()


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


def strides(receipt):
    height = receipt['plan']['rowHeight']
    assert math.isfinite(height) and height > 0 and height == math.floor(height)
    assert receipt['steps'] and set(receipt['steps']) == set(receipt['strides'])
    for key, step in receipt['steps'].items():
        assert 0 < step <= height + .1
        assert 0 <= height - step + .1 < 12
        assert abs(receipt['strides'][key] - height) <= .1


unfiltered = report['unfiltered']
assert unfiltered['total'] == len(registry['colors']) + len(registry['statusRoles'])
assert 0 < unfiltered['submitted'] < unfiltered['total']
assert unfiltered['submitted'] <= math.ceil(unfiltered['rows']['height'] / unfiltered['plan']['rowHeight']) + 2
strides(unfiltered)
for case in report['cases']:
    i = case['scenario']
    w, h = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=w, height=h)
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['window'], case['body'])
    actions = case['actions']
    order = ('save', 'revert', 'reset', 'resume', 'view')
    assert set(actions) == set(order)
    for action in actions.values():
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .02
    for left, right in zip(order, order[1:]):
        assert abs(actions[left]['y'] - actions[right]['y']) <= .02
        assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .02
    pane = case['panes']
    pane_modes.add(pane['columns'])
    assert pane['height'] <= .7 * case['body']['height'] + 1.1
    assert abs(case['palette']['width'] - pane['paletteWidth']) <= 1.1
    assert abs(case['contrast']['width'] - pane['contrastWidth']) <= 1.1
    assert abs(case['palette']['height'] - pane['height']) <= 1.1
    assert abs(case['contrast']['height'] - pane['height']) <= 1.1
    if pane['columns']:
        assert case['palette']['x'] + case['palette']['width'] <= case['contrast']['x'] + .02
    assert case['rows']['height'] <= .65 * case['palette']['height'] + 1.1
    if ids is None:
        ids = case['ids']
        assert set(ids) == {f'status:Warning:{axis}' for axis in 'RGBA'}
        assert len(set(ids.values())) == 4
    assert case['ids'] == ids
    plan = case['plan']
    columns.add(plan['columns'])
    assert plan['rows'] == 4 // plan['columns']
    assert set(case['channels']) == set('RGBA')
    for rect in case['channels'].values():
        assert rect['width'] > 0 and rect['height'] > 0
        assert rect['x'] >= case['rows']['x'] - .1
        assert rect['x'] + rect['width'] <= case['rows']['x'] + case['rows']['width'] + .1
        assert abs(rect['width'] - plan['fieldWidth']) <= 1.1
    strides(case)
assert columns == {1, 2, 4} and pane_modes == {True, False}
warning = report['warning']
assert .35 < warning[0] < .65 and warning[1:] == [.2, .3, 1]
ordinary = json.loads((out / 'ordinary-preferences.json').read_text(encoding='utf-8'))
assert ordinary['appearance']['theme'] == 'Dark'
assert ordinary['appearance']['colors'] == ordinary['appearance']['statusColors'] == {}
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
assert saved['appearance']['theme'] == 'Dark'
assert saved['appearance']['colors'] == {keys['Text']: [.2, .2, .2, 1], keys['WindowBg']: [.2, .2, .2, 1]}
assert saved['appearance']['statusColors'] == {'Warning': warning}
assert saved['appearance']['globalScale'] == 1.25 and saved['workspace']['width'] == 1234
print('Themes pane/channel/stride geometry, exact private RGBA, safe preview and save recovery replay PASS')
