"""Deferred independent Find/Trace geometry, clipped identity and committed fixture replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'find-trace-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('findGuardRetryCancel', 'traceSelectionGuard', 'fullCopyCallbacks', 'emptyCopyBlocked', 'cachedQueries', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalClipboardVerified', 'physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert 0 < report['unfilteredRows'] < 400
assert report['unfilteredRows'] <= math.ceil(report['unfilteredHeight'] / report['unfilteredStride']) + 2
assert len(report['cases']) == 18
forms = ('find-results', 'find-filters', 'trace')
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in forms}
sizes = ((1400, 1200), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
orders = {'find': (('navigate', 'section', 'clear'),),
          'trace': (('copy-trace', 'voxel', 'control'), ('copy-value', 'previous', 'next'))}
find_key, find_id, trace_id, trace_row = None, None, None, None


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i, form = case['scenario'], case['form']
    width, height = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=width, height=height)
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    host = case['workspace'] if form == 'trace' else case['window']
    contains(host, case['body'])
    rows = orders['trace' if form == 'trace' else 'find']
    actions = case['actions']
    assert set(actions) == {key for row in rows for key in row}
    for row in rows:
        for key in row:
            contains(host, actions[key])
            assert actions[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
        for left, right in zip(row, row[1:]):
            assert abs(actions[left]['y'] - actions[right]['y']) <= .1
            assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .1
    if form.startswith('find'):
        contains(case['window'], case['search'])
        contains(case['window'], case['selector'])
        if find_key is None:
            find_key = case['selected']
        assert case['selected'] == find_key
        if form == 'find-results':
            assert case['rows'] == 1 and set(case['ids']) == {find_key}
            if find_id is None:
                find_id = case['ids'][find_key]
            assert case['ids'][find_key] == find_id
            contains(case['body'], case['results'])
        else:
            assert case['rows'] == 0 and case['ids'] == {} and case['results'] is None
    else:
        assert actions['copy-trace']['y'] + actions['copy-trace']['height'] <= actions['copy-value']['y'] + .1
        if trace_id is None:
            trace_id, trace_row = case['rowId'], case['selectedRow']
        assert case['rowId'] == trace_id and case['selectedRow'] == trace_row
        assert case['rows'] == 1 and case['stride'] == math.ceil(case['stride'])
        assert 0 <= case['stepRows'] <= math.ceil(case['steps']['height'] / case['stride']) + 2
        assert case['steps']['height'] <= .2 * case['body']['height'] + 1.1
        assert case['values']['height'] <= .3 * case['body']['height'] + 1.1
        for rect in (case['steps'], case['values']):
            assert rect['width'] > 0 and rect['height'] > 0
            assert rect['x'] >= case['body']['x'] - .1
            assert rect['x'] + rect['width'] <= case['body']['x'] + case['body']['width'] + .1
contains(report['narrowGuard']['work'], report['narrowGuard']['modal'])
project = json.loads((out / 'find-trace-project.json').read_text(encoding='utf-8'))
trace = json.loads((out / 'trace-result.json').read_text(encoding='utf-8'))
assert len(project['noise2D']) == 401
assert project['noise2D'][0]['id'] == 'field' and project['noise2D'][-1]['id'] == 'needle'
assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
assert len(trace['steps']) == 2 and all(step['field'] == 22 for step in trace['steps'])
print('Find/Trace persistent layout, stable results, owned guards, cached projections and analytical fixture replay PASS')
