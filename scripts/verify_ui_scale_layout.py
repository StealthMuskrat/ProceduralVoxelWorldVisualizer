"""Deferred independent Appearance geometry, exact raw input and save/retry receipt replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'ui-scale-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic'
for key in ('ordinarySaveExcludedDraft', 'problemNavigationPreserved', 'cachedFeedback',
            'writeRetry', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert report['saveCallbacks'] == report['themeCallbacks'] == 1
registry = json.loads((root / 'registries/ui-scale-targets.json').read_text(encoding='utf-8'))
sizes = ((1400, 1000), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
globals_ = (1, .5, 1, 2.5, 2.5, 1)
windows = (1, 1, 1, 1, 2.5, 1)
assert len(report['cases']) == 6
assert {case['scenario'] for case in report['cases']} == set(range(6))
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
    assert case['fontGlobalScale'] == globals_[i]
    assert case['fontWindowScale'] == windows[i]
    contains(case['window'], case['category'])
    contains(case['window'], case['body'])
    actions = case['actions']
    assert set(actions) == {'apply', 'revert', 'invalid'}
    ordered = [actions[key] for key in ('apply', 'revert', 'invalid')]
    for action in ordered:
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .02
    for left, right in zip(ordered, ordered[1:]):
        assert abs(left['y'] - right['y']) <= .02
        assert left['x'] + left['width'] <= right['x'] + .02
    raw = case['raw']
    assert raw['global'] == '+1.25' and set(raw['enabled']) == {'Help', 'Logs'}
    assert set(raw['windows']) == set(registry['windows'])
    assert raw['windows']['Help'] == '-' and raw['windows']['Logs'] == '2.000'
    assert all(value == '1.0' for name, value in raw['windows'].items() if name not in ('Help', 'Logs'))
    if ids is None:
        ids = case['ids']
        assert set(ids) == {'Global', 'Global-slider', 'Logs', 'Logs-slider'}
        assert len(set(ids.values())) == 4
    assert case['ids'] == ids
    assert set(case['rows']) == {'Global', 'Logs'}
    for row in case['rows'].values():
        assert math.isfinite(row['sliderWidth']) and row['sliderWidth'] > 0
        assert math.isfinite(row['inputWidth']) and row['inputWidth'] > 0
        if not row['inline']:
            assert row['sliderWidth'] == row['inputWidth']
    orientations.add(case['rows']['Global']['inline'])
    row_height = case['targetRowHeight']
    assert math.isfinite(row_height) and row_height > 0
    assert 2 * row_height - 1.1 <= case['targets']['height'] <= 6 * row_height + 1.1
assert orientations == {True, False}
ordinary = json.loads((out / 'ordinary-preferences.json').read_text(encoding='utf-8'))
assert ordinary['appearance']['globalScale'] == 1
assert ordinary['appearance']['windowScales'] == {'Future Window': 1.75}
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
assert saved['appearance']['globalScale'] == 1.25
assert saved['appearance']['windowScales'] == {'Help': 1.5, 'Logs': 2.0, 'Future Window': 2.0}
assert saved['workspace']['width'] == 1234
print('Appearance footer/body geometry, raw fields/IDs, ordinary save exclusion and corrected retry replay PASS')
