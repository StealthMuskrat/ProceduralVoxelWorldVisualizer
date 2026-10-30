"""Deferred independent Profiler geometry, exact capture-input and frozen-export replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'profile-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('invalidStartPreservedCapture', 'correctedStart', 'reviewSurvivedClear', 'protectedProject',
            'failedExportRetry', 'acceptedConsumed', 'projectPreserved', 'cachedRows'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert 0 < report['unfilteredRows'] < 200
assert report['unfilteredRows'] <= math.ceil(report['unfilteredHeight'] / report['unfilteredStride']) + 2
assert len(report['cases']) == 18
sections = ('Capture & runtime', 'Measurements', 'Export')
assert {(c['scenario'], c['section']) for c in report['cases']} == {
    (i, section) for i in range(6) for section in sections}
sizes = ((1400, 1200), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
global_scales, window_scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
rows = (('start', 'stop', 'clear', 'problem'), ('prepare-export', 'discard-export', 'export-json', 'export-csv'))
fields = {'Capture & runtime': {'DURATION', 'SAMPLES', 'SERIES', 'STRIDE'},
          'Measurements': {'filter'}, 'Export': {'export-path'}}
ids, series_ids = {}, None


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


for case in report['cases']:
    i, section = case['scenario'], case['section']
    width, height = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=width, height=height)
    assert case['globalScale'] == global_scales[i] and case['windowScale'] == window_scales[i]
    contains(case['window'], case['body'])
    contains(case['window'], case['selector'])
    assert case['raw'] == {'DURATION': '+0060000', 'SAMPLES': '+0002048', 'SERIES': '512', 'STRIDE': '-'}
    assert set(case['actions']) == {key for row in rows for key in row}
    for row in rows:
        for key in row:
            contains(case['window'], case['actions'][key])
            assert case['actions'][key]['y'] >= case['body']['y'] + case['body']['height'] - .02
        for left, right in zip(row, row[1:]):
            a, b = case['actions'][left], case['actions'][right]
            assert abs(a['y'] - b['y']) <= .02
            assert a['x'] + a['width'] <= b['x'] + .02
    assert case['actions']['start']['y'] + case['actions']['start']['height'] <= case['actions']['prepare-export']['y'] + .02
    assert set(case['ids']) == set(case['inputs']) == fields[section]
    if section not in ids:
        ids[section] = case['ids']
    assert case['ids'] == ids[section]
    for rect in case['inputs'].values():
        assert rect['width'] > 0 and rect['height'] > 0
        assert rect['x'] >= case['body']['x'] - .1
        assert rect['x'] + rect['width'] <= case['body']['x'] + case['body']['width'] + .1
    if section == 'Measurements':
        assert case['rows'] == 1 and len(case['rowIds']) == 1
        if series_ids is None:
            series_ids = case['rowIds']
        assert case['rowIds'] == series_ids
        assert case['series']['height'] <= .4 * case['body']['height'] + 1.1
        assert case['stride'] == math.ceil(case['stride']) and case['stride'] > 0
    else:
        assert case['rows'] == 0 and case['rowIds'] == {} and case['series'] is None
capture = json.loads((out / 'reviewed.json').read_text(encoding='utf-8'))
assert capture['state'] == 'STOPPED'
assert len(capture['samples']) == len(capture['series']) == 200
assert sorted(sample['value'] for sample in capture['samples']) == list(range(200))
assert all(sample['kind'] == 'QUEUE_DEPTH' and sample['contextId'] == 7 for sample in capture['samples'])
assert sum(sample['label'] == 'needle###雪' for sample in capture['samples']) == 1
assert capture['options']['maxSeries'] == 512 and capture['options']['operationStride'] == 256
project = json.loads((out / 'protected-project.json').read_text(encoding='utf-8'))
assert project['noise2D'][0]['id'] == 'committed'
print('Profiler geometry, exact next-capture input, stable series, clipped rows and frozen export replay PASS')
