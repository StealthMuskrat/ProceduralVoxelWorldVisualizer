"""Deferred independent Application Settings geometry/raw-ownership receipt replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'settings-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('invalidApplyBlocked', 'hiddenInvalidNavigation', 'writeRetry', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert len(report['cases']) == 6
assert {case['scenario'] for case in report['cases']} == set(range(6))
sizes = ((1400, 1000), (360, 640), (360, 640), (720, 640), (300, 240), (1000, 300))
global_scales = (1, 1, 2.5, 2.5, 1, 1)
window_scales = (1, 1, 1, 2.5, 1, 1)
ids = None
orientations = set()


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


for case in report['cases']:
    i = case['scenario']
    w, h = sizes[i]
    assert case['window'] == dict(x=0, y=0, width=w, height=h)
    assert case['globalScale'] == global_scales[i]
    assert case['windowScale'] == window_scales[i]
    assert case['raw'] == ['1e-']
    if ids is None:
        ids = case['ids']
        assert set(ids) == {'slider-0', 'value-0'}
        assert len(set(ids.values())) == 2
    assert ids == case['ids']
    contains(case['window'], case['body'])
    actions = case['actions']
    assert set(actions) == {'apply', 'revert', 'builtins', 'invalid-value'}
    ordered = [actions[key] for key in ('apply', 'revert', 'builtins', 'invalid-value')]
    for action in ordered:
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .02
    for left, right in zip(ordered, ordered[1:]):
        assert abs(left['y'] - right['y']) <= .02
        assert left['x'] + left['width'] <= right['x'] + .02
    plan = case['panes']
    orientations.add(plan['columns'])
    results, detail = case['results'], case['detail']
    for rect, prefix in ((results, 'results'), (detail, 'detail')):
        assert abs(rect['width'] - plan[prefix + 'Width']) <= 1.1
        assert abs(rect['height'] - plan[prefix + 'Height']) <= 1.1
    if plan['columns']:
        assert results['x'] + results['width'] <= detail['x'] + .02
        assert abs(results['y'] - detail['y']) <= .02
    else:
        assert results['y'] + results['height'] <= detail['y'] + .02
        assert abs(results['x'] - detail['x']) <= .02
assert orientations == {True, False}
profile = json.loads((out / 'app-config.json').read_text(encoding='utf-8'))
assert profile['creationDefaults'] == {'noise2D.algorithm.CONSTANT.value': 1e-9}
print('Settings footer/pane geometry, stable field IDs, exact invalid text and corrected retry replay PASS')
