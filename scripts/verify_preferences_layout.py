"""Deferred independent three-form geometry, exact draft retention and preference retry replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'preferences-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('ordinarySaveExcludedDrafts', 'independentWriteRetry', 'projectPreserved', 'runtimeOwnerPreserved'):
    assert report[key] is True, key
for key in ('physicalCenterVerified', 'physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert report['defaultCallbacks'] == report['centerCallbacks'] == 1
assert len(report['cases']) == 18
assert {(case['scenario'], case['form']) for case in report['cases']} == {
    (i, form) for i in range(6) for form in ('limits', 'trust', 'workspace')}
sizes = ((1400, 1200), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
globals_ = (1, .5, 1, 2.5, 2.5, 1)
windows = (1, 1, 1, 1, 2.5, 1)
orders = {'limits': ('apply', 'revert', 'problem'), 'trust': ('apply', 'revert', 'fields'),
          'workspace': ('defaults', 'center')}
performance_fields = {'WORKERS', 'MAX_DIMENSION', 'DEFAULT_EXTENT', 'PREVIEW_CELLS', 'CACHE_MIB',
                      'ENGINE_MIB', 'SCENE_CPU_MIB', 'SCENE_GPU_MIB', 'UPLOAD_KIB', 'RENDER_DISTANCE',
                      'VERTICAL_DISTANCE', 'LOD_START', 'LOD_QUALITY', 'STREAM_CHUNKS'}
visibility = {name: name != 'Logs' for name in ('Find', 'Logs', 'Validation', 'Help', 'Notifications', 'Profiler', 'Themes')}
raw_limits = None
ids = {}
raw_origins = 'HTTPS://UPDATES.EXAMPLE.TEST:443/\nhttps://releases.example.test\nhttps://updates.example.test'


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


for case in report['cases']:
    i, form = case['scenario'], case['form']
    width, height = sizes[i]
    assert case['window'] == dict(x=0, y=0, width=width, height=height)
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['window'], case['category'])
    contains(case['window'], case['body'])
    actions = case['actions']
    assert set(actions) == set(orders[form])
    for action in actions.values():
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .02
    for left, right in zip(orders[form], orders[form][1:]):
        assert abs(actions[left]['y'] - actions[right]['y']) <= .02
        assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .02
    assert set(case['limits']) == performance_fields
    if raw_limits is None:
        raw_limits = case['limits']
    assert case['limits'] == raw_limits
    assert case['limits']['PREVIEW_CELLS'] == str(2**63 - 1)
    assert case['limits']['RENDER_DISTANCE'] == '-'
    assert case['trust'] == {'endpoint': 'http://updates.example.test/meta.json', 'origins': raw_origins}
    assert case['visibility'] == visibility
    if form != 'workspace':
        contains(case['window'], case['selector'])
        assert case['section'] == ('Performance limits' if form == 'limits' else 'Update trust')
        if form not in ids:
            ids[form] = case['ids']
            assert len(set(ids[form].values())) == len(ids[form])
        assert case['ids'] == ids[form]
        assert set(case['ids']) == (performance_fields if form == 'limits' else {'endpoint', 'origins'})
    if form == 'trust':
        for rect in case['inputs'].values():
            assert rect['width'] > 0 and rect['height'] > 0
            assert rect['x'] >= case['body']['x'] - .1
            assert rect['x'] + rect['width'] <= case['body']['x'] + case['body']['width'] + .1
        assert case['inputs']['origins']['height'] <= .4 * case['body']['height'] + 1.1
ordinary = json.loads((out / 'ordinary-preferences.json').read_text(encoding='utf-8'))
assert ordinary['performance']['previewCellBudget'] != 2**63 - 1
assert ordinary['performance']['updateEndpoint'] == '' and ordinary['performance']['trustedOrigins'] == []
assert set(ordinary['workspace']['visibleWindows']) == {name for name, shown in visibility.items() if shown}
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
assert type(saved['performance']['previewCellBudget']) is int
assert saved['performance']['previewCellBudget'] == 2**63 - 1
assert saved['performance']['renderDistance'] == 3
assert saved['performance']['updateEndpoint'] == 'https://updates.example.test/meta.json'
assert saved['performance']['trustedOrigins'] == ['https://updates.example.test', 'https://releases.example.test']
assert saved['workspace']['width'] == 1234 and saved['appearance']['theme'] == 'Light'
assert saved['appearance']['globalScale'] == 1.25
assert set(saved['workspace']['visibleWindows']) == {name for name, shown in visibility.items() if shown}
print('Performance/trust/workspace geometry, independent exact drafts, callback routing and atomic retry replay PASS')
