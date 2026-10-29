"""Deferred independent replay of native Curve Editor geometry and action/wheel ownership."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'curve-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for flag in ('projectPreserved', 'plotWheelOnlyZooms', 'bodyWheelOnlyScrolls',
             'resizeCancelledPan', 'collapsePreservedNormal'):
    assert report[flag] is True, flag
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']
expected = {'normal': (720, 600, 1, 1), 'narrow': (360, 240, 2.5, 1),
            'product-6.25': (720, 480, 2.5, 2.5)}
assert len(report['cases']) == len(expected)
assert {case['name'] for case in report['cases']} == set(expected)
for case in report['cases']:
    width, height, global_scale, window_scale = expected[case['name']]
    assert case['workArea'] == dict(x=0, y=0, width=width, height=height)
    assert case['globalScale'] == global_scale and case['windowScale'] == window_scale
    bounds, body = case['bounds'], case['body']
    for rect in (bounds, body):
        assert all(math.isfinite(v) for v in rect.values())
        assert rect['width'] > 0 and rect['height'] > 0
    assert bounds['x'] >= 8 - .02 and bounds['y'] >= 8 - .02
    assert bounds['x'] + bounds['width'] <= width - 8 + .02
    assert bounds['y'] + bounds['height'] <= height - 8 + .02
    assert body['x'] >= bounds['x'] and body['y'] > bounds['y']
    assert body['x'] + body['width'] <= bounds['x'] + bounds['width'] + .02
    x, y = case['cancel']
    assert math.isfinite(x) and math.isfinite(y)
    assert bounds['x'] < x < bounds['x'] + bounds['width']
    assert body['y'] + body['height'] <= y < bounds['y'] + bounds['height']
    assert 0 < case['plotWidth'] <= body['width']
    assert 0 < case['plotHeight'] <= body['height'] * .65 + .02
print('Curve native bounds, persistent Cancel, plot/body wheel separation and pan/collapse receipts PASS')
