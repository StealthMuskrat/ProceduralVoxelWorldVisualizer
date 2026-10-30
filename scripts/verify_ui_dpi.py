"""Deferred independent dense-font and synthetic DPI composition/nested-scope receipts."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'ui-dpi-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['dpiSource'] == 'synthetic'
assert report['projectPreserved'] is True
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']
assert not report['gpuTextureVerified']
atlas = report['atlas']
assert atlas['rasterSize'] == 52 and atlas['fontScale'] == .25
assert atlas['rasterSize'] * atlas['fontScale'] == 13
assert 0 < atlas['width'] <= 4096 and 0 < atlas['height'] <= 4096
assert atlas['rgbaBytes'] == atlas['width'] * atlas['height'] * 4 <= 16 * 1024 * 1024
expected = {'standard': (1, 1, 1, 1), 'pixel-dpi-2': (2, 1, 1.25, 1.5),
            'retina-2': (2, 2, 1.25, 1.5), 'pixel-dpi-3': (3, 1, 1, 1),
            'user-product-6.25': (2, 1, 2.5, 2.5)}
assert len(report['cases']) == len(expected)
assert {case['name'] for case in report['cases']} == set(expected)
for case in report['cases']:
    content, framebuffer, global_user, window_user = expected[case['name']]
    assert (case['mainContentScale'], case['framebufferScale'], case['userGlobal'], case['windowMultiplier']) == (content, framebuffer, global_user, window_user)
    assert math.isclose(case['fontGlobalScale'], global_user * content / framebuffer, rel_tol=1e-6)
    assert set(case['fontSizes']) == {'root', 'child-1', 'child-2', 'child-3', 'combo', 'popup'}
    logical_height = 13 * global_user * window_user * content / framebuffer
    for measured in case['fontSizes'].values():
        assert math.isfinite(measured) and math.isclose(measured, logical_height, rel_tol=1e-6, abs_tol=.001)
    physical_height = logical_height * framebuffer
    assert math.isclose(physical_height, 13 * global_user * window_user * content, rel_tol=1e-6)
print('Dense atlas, synthetic DPI normalization and nested child/combo/popup font receipts PASS')
