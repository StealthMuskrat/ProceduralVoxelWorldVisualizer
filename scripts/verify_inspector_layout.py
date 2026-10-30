"""Deferred independent Inspector geometry and committed analytical trace receipt checks."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'inspector-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('invalidFocusRecovery', 'pagePreservedRequest', 'cancelRetainedTrace',
            'restoreWithoutTrace', 'bookmarkSeparated', 'refreshPreservedEnteredXYZ',
            'foreignRefreshBlocked', 'missRecovery', 'fullCopyCallback',
            'hiddenPublicationInvalidated', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified',
            'physicalClipboardVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
sizes = ((1200, 900), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 320))
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
assert len(report['cases']) == 12
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in ('Coordinates', 'Trace')}


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


def actions(host, body, boxes, rows):
    contains(host, body)
    assert set(boxes) == {key for row in rows for key in row}
    previous_bottom = body['y'] + body['height']
    for row in rows:
        for key in row:
            contains(host, boxes[key])
            assert boxes[key]['y'] >= previous_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
        previous_bottom = max(boxes[key]['y'] + boxes[key]['height'] for key in row)


ids = None
for case in report['cases']:
    i = case['scenario']
    w, h = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=w, height=h)
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    actions(case['window'], case['body'], case['actions'],
            (('trace-coordinate', 'trace-cancel', 'trace-bookmark'),
             ('trace-restore', 'trace-refresh', 'trace-page')))
    if case['form'] == 'Coordinates':
        assert case['columns'] in (1, 2, 3)
        assert set(case['fields']) == set(case['ids']) == {'X', 'Y', 'Z'}
        if ids is None:
            ids = case['ids']
        assert case['ids'] == ids
        # Scrolling may move virtual Y positions; each input still fits the body width.
        for field in case['fields'].values():
            assert field['width'] > 0 and field['height'] > 0
            assert all(math.isfinite(v) for v in field.values())
            assert field['x'] >= case['body']['x'] - .1
            assert field['x'] + field['width'] <= case['body']['x'] + case['body']['width'] + .1
    else:
        contains(case['body'], case['traceWorkspace'])
        assert abs(case['traceWorkspace']['height'] - case['body']['height']) <= 1.1
        actions(case['traceWorkspace'], case['traceBody'], case['traceActions'],
                (('copy-trace', 'voxel', 'control'), ('copy-value', 'previous', 'next')))
project = json.loads((out / 'inspector-layout-project.json').read_text(encoding='utf-8'))
trace = json.loads((out / 'trace-result.json').read_text(encoding='utf-8'))
assert project['noise2D'][0]['id'] == 'field' and len(project['noise2D']) == 1
assert project['noise2D'][0]['algorithm']['parameters']['value'] == 2
assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
assert len(trace['steps']) == 2
# Source 2 + 2 * transformed X(2+1) + Z(3), then multiply by 2 = 22.
assert all(step['field'] == 22 for step in trace['steps'])
assert trace['steps'][0]['result'] == 1 and trace['steps'][1]['result'] == 1
print('Inspector persistent geometry, stable XYZ, recovery, ownership and analytical trace receipt PASS')
