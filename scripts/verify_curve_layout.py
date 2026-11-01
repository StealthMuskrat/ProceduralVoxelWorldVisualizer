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
    assert set(case['numericRows']) == {'min', 'max', 'displayMin', 'displayMax',
                                       'stepX', 'stepY', 'x', 'y', 'inTangent', 'outTangent'}
    identities = []
    for row in case['numericRows'].values():
        slider, text = row['slider'], row['input']
        assert row['minimum'] <= row['maximum']
        for rect in (slider, text):
            assert all(math.isfinite(v) for v in rect.values())
            assert rect['width'] > 0 and rect['height'] > 0
            assert body['x'] - 1.1 <= rect['x']
            assert rect['x'] + rect['width'] <= body['x'] + body['width'] + 1.1
        if row['inline']:
            assert abs(slider['y'] - text['y']) <= 1.1
            assert slider['x'] + slider['width'] <= text['x'] + 1.1
        else:
            assert abs(slider['x'] - text['x']) <= 1.1
            assert slider['y'] + slider['height'] <= text['y'] + 1.1
        identities.extend((row['sliderId'], row['inputId']))
    assert all(identities) and len(set(identities)) == 20
print('Curve native bounds, persistent Cancel, plot/body wheel separation and pan/collapse receipts PASS')
