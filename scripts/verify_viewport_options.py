"""Deferred independent finite option/Inspect geometry, exact routing and isolation replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('viewport-options-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'triggerSourceScoped', 'fieldCurrentScoped', 'documentLifetimeScoped',
            'hiddenReopenScoped', 'completeReviewRechecked', 'callbacksBalanced', 'failureRetryScoped',
            'successorPreserved', 'inspectRouted', 'privatePreserved'):
    assert report[key] is True, key
assert report['physicalDpiVerified'] is False and report['gpuModeVerified'] is False
options = {'DISPLAY': ['Grayscale', 'Heatmap', 'Contour', 'Threshold', 'Histogram'],
           'REPRESENTATION': ['Threshold Voxels', 'Isosurface', 'Volume', 'Point Cloud', 'Slices'],
           'SLICE_NORMAL': ['X', 'Y', 'Z'], 'STAGE_PREVIEW': ['Final', 'Before', 'After', 'Only Changes']}
assert len(report['cases']) == 30
assert {(c['form'], c['scenario']) for c in report['cases']} == {
    (form, i) for form in list(options) + ['INSPECT'] for i in range(6)}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1


buttons, rows = {}, {}
for case in report['cases']:
    form, i = case['form'], case['scenario']
    assert case['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert case['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    contains(case['window'], case['button'])
    assert case['buttonId'] != 0
    if form not in buttons:
        buttons[form] = case['buttonId']
    assert buttons[form] == case['buttonId']
    if form == 'INSPECT':
        continue
    contains(case['popup'], case['list'])
    assert set(case['ids']) == set(case['rows']) == set(options[form])
    assert all(case['ids'].values()) and len(set(case['ids'].values())) == len(options[form])
    assert case['stride'] > 0 and case['stride'] == math.ceil(case['stride'])
    # Large-font rows are retained in a scrolling child; offscreen row submission is not visibility.
    for box in case['rows'].values():
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for a, b in zip(options[form], options[form][1:]):
        assert abs(case['rows'][b]['y'] - case['rows'][a]['y'] - case['stride']) <= 1.1
    if form not in rows:
        rows[form] = case['ids']
    assert rows[form] == case['ids']
assert len(set(buttons.values())) == 5
expected = [dict(form=form, value=value) for form, values in options.items() for value in values]
expected += [dict(form='DISPLAY', value=value) for value in ('Heatmap', 'Contour')] + [dict(form='INSPECT', value='open')]
assert report['deliveries'] == expected and len(expected) == 20
events = read('option-events.json')
assert events['counts'] == dict(retained=3, unread=3, actionRequired=3, evicted=0)
assert {(entry['code'], entry['message'], entry['subsystem']) for entry in events['entries']} == {
    ('VIEWPORT_OPTION_FAILED', 'viewport option boundary', 'RENDERING'),
    ('VIEWPORT_OPTION_FAILED', 'viewport option successor', 'RENDERING'),
    ('VIEWPORT_COMMAND_FAILED', 'inspect boundary', 'APPLICATION')}
for entry in events['entries']:
    assert entry['count'] == 1 and entry['severity'] == 'ERROR'
    assert entry['actionRequired'] and not entry['read'] and not entry['dismissed'] and entry['target'] is None
assert read('option-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
print('Viewport finite option/Inspect geometry, source-owned routes, explicit retries and full persistence isolation PASS')
