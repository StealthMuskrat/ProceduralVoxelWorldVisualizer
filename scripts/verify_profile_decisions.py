"""Deferred independent profiler/Debug footer geometry and exact frozen-capture ownership replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'profile-decisions-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('escapePreserved', 'heldCaptureSuccessorsBlocked', 'heldPrepareBlocked',
            'heldReviewSuccessorsBlocked', 'independentReviewContext', 'hiddenContextsReleased',
            'failedRetriesRetained', 'consumedFormatsBlocked', 'projectPreserved'):
    assert report[key] is True, key
assert report['debugStarts'] == 1 and report['debugOpens'] == 2
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
sections = {'Profiler': ('Capture & runtime', 'Measurements', 'Export'),
            'Debug': ('General', 'Generation', 'World-Chunks', 'Rendering', 'Memory', 'Scheduler', 'LOD', 'Diagnostics')}
assert len(report['cases']) == 66
assert {(c['panel'], c['section'], c['scenario']) for c in report['cases']} == {
    (p, s, i) for p, names in sections.items() for s in names for i in range(6)}
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
    i, panel = case['scenario'], case['panel']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['window'], case['body'])
    rows = (('start', 'stop', 'clear', 'problem'), ('prepare-export', 'discard-export', 'export-json', 'export-csv')) if panel == 'Profiler' else (('capture', 'profiler'),)
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == {key for row in rows for key in row}
    assert len(set(case['ids'].values())) == len(boxes) and all(case['ids'].values())
    previous_bottom = case['body']['y'] + case['body']['height']
    for row in rows:
        for key in row:
            contains(case['window'], boxes[key])
            assert boxes[key]['y'] >= previous_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
        previous_bottom = max(boxes[key]['y'] + boxes[key]['height'] for key in row)
    key = (panel, case['section'])
    if key not in identities:
        identities[key] = case['ids']
    assert identities[key] == case['ids']
captures = []
for name, value in (('reviewed', 123), ('replacement', 999)):
    capture = json.loads((out / (name + '.json')).read_text(encoding='utf-8'))
    assert capture['formatVersion'] == 1 and capture['state'] == 'STOPPED'
    assert capture['options'] == dict(durationMillis=60000, maxSamples=2048, maxSeries=128,
                                     operationStride=1, worldContributions=False)
    sample, = capture['samples']
    assert sample['value'] == value and sample['label'] == 'sample-' + str(value)
    assert sample['kind'] == 'QUEUE_DEPTH' and sample['unit'] == 'COUNT' and sample['contextId'] == 7
    series, = capture['series']
    assert series['count'] == 1 and series['minimum'] == series['maximum'] == value
    assert series['p50'] == series['p95'] == series['p99'] == value
    captures.append(capture)
assert captures[0]['captureId'] != captures[1]['captureId']
assert not (out / 'escape-output.json').exists() and not (out / 'consumed.csv').exists()
project = json.loads((out / 'unchanged-project.json').read_text(encoding='utf-8'))
assert project == json.loads((out / 'protected-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['noise3D'] and not project['worldGenerator']['layers']
assert json.loads((out / 'preferences.json').read_text(encoding='utf-8')) == json.loads((out / 'preferences-before.json').read_text(encoding='utf-8'))
print('Profiler/Debug footer geometry, independent frozen reviews and unchanged project replay PASS')
