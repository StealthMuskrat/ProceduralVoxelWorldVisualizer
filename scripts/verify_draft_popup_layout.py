"""Deferred independent geometry/ownership receipt replay; never physical DPI evidence."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'draft-popup-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
assert report['projectPreserved'] and report['selections'] == 4
assert report['cancelledBeforeDrawPreserved']
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']
assert not report['gpuFontTextureVerified']
assert report['followingWindow'] == dict(x=31, y=37, width=211, height=133)
expected = {(kind, scenario) for kind in ('picker', 'reference', 'source') for scenario in range(4)}
assert len(report['cases']) == len(expected)
assert {(case['kind'], case['scenario']) for case in report['cases']} == expected


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


for case in report['cases']:
    scenario = case['scenario']
    width, height = (720, 480) if scenario == 3 else (360, 240)
    assert case['workArea'] == dict(x=0, y=0, width=width, height=height)
    assert case['globalScale'] == (.5, 1, 2.5, 2.5)[scenario]
    assert case['windowScale'] == (2.5 if scenario == 3 else 1)
    contains(dict(x=8, y=8, width=width-16, height=height-16), case['bounds'])
    if case['kind'] == 'picker':
        contains(case['bounds'], case['query'])
        contains(case['bounds'], case['list'])
        assert 0 < case['submitted'] < 96
    else:
        assert case['failedApplyRetained'] and case['escapePreserved']
        assert set(case['actions']) == {'APPLY', 'DISCARD', 'CANCEL'}
        actions = [case['actions'][key] for key in ('APPLY', 'DISCARD', 'CANCEL')]
        for action in actions:
            contains(case['bounds'], action)
        for left, right in zip(actions, actions[1:]):
            assert abs(left['y'] - right['y']) <= .02
            assert left['x'] + left['width'] <= right['x'] + .02
print('Draft popup geometry, local key callbacks, persistent guard actions/feedback and Escape replay PASS')
