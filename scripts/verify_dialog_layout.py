"""Deferred independent native modal bounds/viewport and private-state replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'dialog-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('projectPreserved', 'cancelReachableByWheel', 'sameKindReopen', 'removedHostFallback'):
    assert report[key] is True, key
assert report['physicalDetachedVerified'] is False
assert report['physicalDpiVerified'] is False
expected = {'draft-480': (480, 320, 2.5), 'path-360': (360, 240, 2.5),
            'reopen-720': (720, 480, 1.25), 'import-360-fallback': (360, 240, 2.5)}
assert len(report['cases']) == len(expected)
assert {case['name'] for case in report['cases']} == set(expected)
for case in report['cases']:
    width, height, scale = expected[case['name']]
    work, bounds = case['workArea'], case['bounds']
    assert work == {'x': 0.0, 'y': 0.0, 'width': width, 'height': height}
    assert case['scale'] == scale
    assert all(math.isfinite(value) for value in bounds.values())
    assert bounds['width'] >= 160 and bounds['height'] >= 120
    assert bounds['x'] >= work['x'] + 8 - .02
    assert bounds['y'] >= work['y'] + 8 - .02
    assert bounds['x'] + bounds['width'] <= work['x'] + width - 8 + .02
    assert bounds['y'] + bounds['height'] <= work['y'] + height - 8 + .02
print('Modal native viewport bounds, scaled narrow cases, wheel-to-Cancel and private-state replay PASS')
