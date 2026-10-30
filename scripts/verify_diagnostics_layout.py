"""Deferred independent diagnostic geometry, history state and owned selection replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'diagnostics-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('filteredSelectionPreserved', 'cachedProjection', 'clipboardCallback',
            'readResolveDismissRepeat', 'validationReplacementClearedSelection',
            'committedValidationReadOnly', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalClipboardVerified', 'physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert report['sourceCallbacks'] == 3 and report['logsCallbacks'] == 1
assert 0 < report['unfilteredRows'] < 200
assert report['unfilteredRows'] <= math.ceil(report['unfilteredHeight'] / report['unfilteredStride']) + 2
assert len(report['cases']) == 18
forms = ('logs', 'notices', 'validation')
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in forms}
sizes = ((1400, 1200), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
orders = {'logs': (('copy', 'navigate', 'show'),),
          'notices': (('all-read', 'logs', 'copy'), ('navigate', 'read', 'acknowledge')),
          'validation': (('validate', 'copy', 'navigate'),)}
ids = {}
columns = set()


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


for case in report['cases']:
    i, form = case['scenario'], case['form']
    width, height = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=width, height=height)
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['window'], case['body'])
    actions = case['actions']
    assert set(actions) == {key for row in orders[form] for key in row}
    for row in orders[form]:
        for key in row:
            contains(case['window'], actions[key])
            assert actions[key]['y'] >= case['body']['y'] + case['body']['height'] - .02
        for left, right in zip(row, row[1:]):
            assert abs(actions[left]['y'] - actions[right]['y']) <= .02
            assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .02
    if form == 'notices':
        assert actions['all-read']['y'] + actions['all-read']['height'] <= actions['navigate']['y'] + .02
    for rect in (case['results'], case['detail']):
        assert all(math.isfinite(v) for v in rect.values())
        assert rect['width'] > 0 and rect['height'] > 0
        assert rect['x'] >= case['body']['x'] - 1.1
        assert rect['x'] + rect['width'] <= case['body']['x'] + case['body']['width'] + 1.1
    left, right = case['results'], case['detail']
    columns.add(case['panes']['columns'])
    if case['panes']['columns']:
        assert abs(left['y'] - right['y']) <= 1.1
        assert left['x'] + left['width'] <= right['x'] + 1.1
        assert abs(left['height'] - right['height']) <= 1.1
    else:
        assert abs(left['x'] - right['x']) <= 1.1
        assert left['y'] + left['height'] <= right['y'] + 1.1
    assert case['selectedEvent'] == report['selectedEvent'] and case['selectedIssue'] == 0
    if form not in ids:
        ids[form] = case['selectedRowId']
    assert case['selectedRowId'] == ids[form]
    assert case['stride'] == math.ceil(case['stride']) and case['stride'] > 0
    assert 0 < case['rows'] <= math.ceil(left['height'] / case['stride']) + 2
    if form == 'logs':
        assert case['rows'] == 1
    else:
        assert case['rows'] < 200
assert columns == {True, False}
view = json.loads((out / 'diagnostics-events.json').read_text(encoding='utf-8'))
entries, counts = view['entries'], view['counts']
assert counts['retained'] == len(entries) == 201 and counts['evicted'] == 0
assert counts['unread'] == sum(not e['dismissed'] and not e['read'] for e in entries) == 1
assert counts['actionRequired'] == sum(e['actionRequired'] for e in entries) == 1
selected = next(e for e in entries if e['id'] == report['selectedEvent'])
assert selected['count'] == 2 and selected['actionRequired'] and selected['read'] and not selected['dismissed']
assert selected['code'] == 'FIXTURE_199' and 'fixture-199' in selected['message']
assert any(e['code'] == 'PROJECT_VALID' and not e['read'] for e in entries)
project = json.loads((out / 'diagnostics-project.json').read_text(encoding='utf-8'))
assert project['noise2D'][0]['id'] == 'committed'
assert selected['target']['project'] == project['project']['uuid']
assert selected['target']['resource'] == project['noise2D'][0]['uuid']
print('Diagnostics panes/actions, exact selections, history acknowledgements, cache and read-only validation replay PASS')
