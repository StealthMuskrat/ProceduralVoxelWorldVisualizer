"""Deferred independent control-pane, preview, field-width and scroll/input isolation replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('viewport-controls-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('wheelOwned', 'scrollStable', 'pausedScrollStable', 'finiteInputGuarded', 'numberEdited', 'integerEdited',
            'checkboxEdited', 'resumeRevealed', 'nativeScopesBalanced', 'privatePreserved'):
    assert report[key] is True, key
assert report['resumes'] == 1
assert report['physicalDpiVerified'] is False and report['gpuPreviewVerified'] is False
assert len(report['cases']) == 24
assert {(c['form'], c['scenario']) for c in report['cases']} == {
    (form, i) for form in ('Histogram', 'Volume', 'Slices', 'World') for i in range(6)}


def finite(box):
    assert all(math.isfinite(value) for value in box.values())
    assert box['width'] > 0 and box['height'] > 0


def contains(outer, inner):
    finite(outer)
    finite(inner)
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1


ids = {}
for case in report['cases']:
    form, i = case['form'], case['scenario']
    assert case['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert case['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    controls, image = case['controls'], case['image']
    contains(case['window'], controls)
    contains(case['window'], image)
    assert controls['y'] + controls['height'] <= image['y'] + 1.1
    assert case['rootScroll'] <= 1.1
    split, surface = case['split'], case['surface']
    assert split['controls'] >= 1 and split['preview'] >= 1 and split['gap'] >= 0
    assert split['controls'] <= 1.5 * split['preview'] + 1.1
    assert surface['image'] > 0 and abs(surface['image'] - image['height']) <= 1.1
    if form == 'Histogram':
        contains(case['window'], case['histogram'])
        assert controls['y'] + controls['height'] <= case['histogram']['y'] + 1.1
        assert case['histogram']['y'] + case['histogram']['height'] <= image['y'] + 1.1
    else:
        assert case['histogram'] is None and surface['histogram'] == 0
    if form in ('Histogram', 'Slices'):
        contains(case['window'], case['status'])
        assert image['y'] + image['height'] <= case['status']['y'] + 1.1
        assert surface['status'] > 0
    else:
        assert case['status'] is None and surface['status'] == 0
    assert set(case['fields']) == set(case['ids']) == {'Center X', 'Preview extent', 'Fixed world bounds'}
    assert all(case['ids'].values()) and len(set(case['ids'].values())) == 3
    for field in case['fields'].values():
        finite(field)
        # Vertical positions may be outside the scrolling viewport; horizontal overflow is disallowed.
        assert field['x'] >= controls['x'] - 1.1
        assert field['x'] + field['width'] <= controls['x'] + controls['width'] + 1.1
    if form not in ids:
        ids[form] = case['ids']
    assert ids[form] == case['ids']
assert read('resume-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
print('Viewport controls/preview fit, stable fields, scroll/input ownership, finite edits and persistence isolation PASS')
