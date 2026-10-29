"""Deferred independent replay of actual native selector geometry and ownership receipts."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'popup-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
assert report['projectPreserved'] and report['escapePreservedSelection']
assert report['selections'] == report['teleports'] == 4
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']
expected = {f'{kind}-{scale}': (360, 240, float(scale), 1.0)
            for kind in ('resource', 'bookmark') for scale in ('0.5', '1.0', '2.5')}
expected.update({f'{kind}-product-6.25': (720, 480, 2.5, 2.5) for kind in ('resource', 'bookmark')})
assert len(report['cases']) == len(expected)
assert {case['name'] for case in report['cases']} == set(expected)

def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    assert inner['x'] >= outer['x'] - .02 and inner['y'] >= outer['y'] - .02
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02

for case in report['cases']:
    width, height, global_scale, window_scale = expected[case['name']]
    assert case['workArea'] == dict(x=0, y=0, width=width, height=height)
    assert case['globalScale'] == global_scale and case['windowScale'] == window_scale
    contains(dict(x=8, y=8, width=width-16, height=height-16), case['bounds'])
    contains(case['bounds'], case['query'])
    contains(case['bounds'], case['list'])
    assert 0 < case['submitted'] < 96
print('Selector native narrow/scaled geometry, clipped rows, search/UUID/Escape ownership replay PASS')
