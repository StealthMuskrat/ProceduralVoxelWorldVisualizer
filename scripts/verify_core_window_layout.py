"""Deferred independent core-window and responsive XYZ native geometry receipts."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'core-window-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for flag in ('callerSizesPreserved', 'rawInputAndIdsPreserved', 'signedInputCorrected', 'projectPreserved'):
    assert report[flag] is True
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']

def fitted(area, bounds):
    assert all(math.isfinite(v) for v in bounds.values())
    assert bounds['width'] > 0 and bounds['height'] > 0
    assert bounds['x'] >= area['x'] + 8 - .02 and bounds['y'] >= area['y'] + 8 - .02
    assert bounds['x'] + bounds['width'] <= area['x'] + area['width'] - 8 + .02
    assert bounds['y'] + bounds['height'] <= area['y'] + area['height'] - 8 + .02

expected = {'core-wide': (1280, 900, 1), 'core-narrow': (360, 240, 2.5)}
assert len(report['coreCases']) == 2
assert {case['name'] for case in report['coreCases']} == set(expected)
for case in report['coreCases']:
    width, height, scale = expected[case['name']]
    assert case['workArea'] == dict(x=0, y=0, width=width, height=height)
    assert case['scale'] == scale
    assert set(case['windows']) == {'Resources', 'Properties', 'Voxel Manager'}
    for bounds in case['windows'].values():
        fitted(case['workArea'], bounds)
    if case['name'] == 'core-wide':
        assert (case['windows']['Resources']['width'], case['windows']['Resources']['height']) == (280, 520)
        assert (case['windows']['Properties']['width'], case['windows']['Properties']['height']) == (400, 600)

expected = {'three-columns': (960, 640, 1, 1, 3, False),
            'two-columns': (560, 400, 2.5, 1, 2, False),
            'one-column': (360, 240, 2.5, 1, 1, False),
            'stacked-6.25': (360, 240, 2.5, 2.5, 1, True)}
assert len(report['coordinateCases']) == 4
assert {case['name'] for case in report['coordinateCases']} == set(expected)
for case in report['coordinateCases']:
    width, height, global_scale, window_scale, columns, stacked = expected[case['name']]
    assert case['workArea'] == dict(x=0, y=0, width=width, height=height)
    assert (case['globalScale'], case['windowScale']) == (global_scale, window_scale)
    assert (case['plan']['columns'], case['plan']['stackedLabels']) == (columns, stacked)
    assert 0 < case['plan']['inputWidth'] <= case['bounds']['width']
    fitted(case['workArea'], case['bounds'])
    axes = case['axes']
    assert set(axes) == {'X', 'Y', 'Z'}
    for bounds in axes.values():
        assert all(math.isfinite(v) for v in bounds.values())
        assert bounds['x'] >= case['bounds']['x']
        assert bounds['x'] + bounds['width'] <= case['bounds']['x'] + case['bounds']['width'] + .02
    if columns == 3:
        assert axes['X']['y'] == axes['Y']['y'] == axes['Z']['y']
        assert axes['X']['x'] < axes['Y']['x'] < axes['Z']['x']
    elif columns == 2:
        assert axes['X']['y'] == axes['Y']['y'] < axes['Z']['y']
        assert axes['X']['x'] == axes['Z']['x'] < axes['Y']['x']
    else:
        assert axes['X']['y'] < axes['Y']['y'] < axes['Z']['y']
        assert axes['X']['x'] == axes['Y']['x'] == axes['Z']['x']
print('Core native fitting, caller dimensions, axis-grid geometry and raw-input/identity receipts PASS')
