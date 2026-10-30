"""Deferred independent general-settings geometry, private-text and publication replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'general-settings-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('ordinarySaveExcludedProjectDraft', 'ordinarySaveRetry', 'projectRejectionRetry',
            'projectUndoRedo', 'staleReloadCancel', 'privatePropertiesPreserved', 'runtimeOwnerPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert report['captureStarts'] == 1 and report['profilerOpens'] == 2
assert report['retryCallbacks'] == report['limitCallbacks'] == 1
assert report['diagnosticSections'] == ['General', 'Generation', 'World-Chunks', 'Rendering',
                                        'Memory', 'Scheduler', 'LOD', 'Diagnostics']
assert len(report['cases']) == 24
forms = ('project', 'editor', 'visualization', 'debug')
assert {(c['scenario'], c['form']) for c in report['cases']} == {
    (i, form) for i in range(6) for form in forms}
sizes = ((1400, 1200), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
global_scales = (1, .5, 1, 2.5, 2.5, 1)
window_scales = (1, 1, 1, 1, 2.5, 1)
orders = {'project': ('apply', 'cancel', 'reload', 'problem'), 'editor': ('save',),
          'visualization': ('save', 'limits', 'retry'), 'debug': ('capture', 'profiler')}
fields = {'project': {'seed', 'minY', 'maxY'}, 'editor': {'auto', 'rate'}, 'debug': set(),
          'visualization': {'grid', 'unlit', 'streaming', 'ao', 'fog', 'shadows', 'resolution',
                            'volume-shadows', 'volume-steps', 'indirect'}}
ids = {}


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


for case in report['cases']:
    i, form = case['scenario'], case['form']
    width, height = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=width, height=height)
    assert case['globalScale'] == global_scales[i] and case['windowScale'] == window_scales[i]
    contains(case['window'], case['body'])
    if form == 'project':
        assert case['category'] is None
    else:
        contains(case['window'], case['category'])
    assert case['projectText'] == {'seed': '-', 'minY': '+0002', 'maxY': '-0003'}
    actions = case['actions']
    assert set(actions) == set(orders[form])
    for action in actions.values():
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .02
    for left, right in zip(orders[form], orders[form][1:]):
        assert abs(actions[left]['y'] - actions[right]['y']) <= .02
        assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .02
    assert set(case['ids']) == fields[form] and set(case['inputs']) == fields[form]
    if form not in ids:
        ids[form] = case['ids']
        assert len(set(ids[form].values())) == len(ids[form])
    assert case['ids'] == ids[form]
    for key, rect in case['inputs'].items():
        assert all(math.isfinite(value) for value in rect.values())
        assert rect['width'] > 0 and rect['height'] > 0
        assert rect['x'] >= case['body']['x'] - .1
        assert rect['x'] + rect['width'] <= case['body']['x'] + case['body']['width'] + .1
        # Long labels sit above these fields; native checkbox targets keep their normal size.
        if form == 'project' or key in ('rate', 'resolution', 'volume-steps'):
            assert rect['width'] > .5 * case['body']['width']
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
assert saved['editor'] == {'autoRegenerate': False, 'opportunitiesPerSecond': 20}
assert saved['visualization']['shadows'] is True
assert saved['visualization']['shadowResolution'] == 2048
assert saved['visualization']['volumeShadows'] is True
assert saved['visualization']['volumeShadowSteps'] == 32
project = json.loads((out / 'general-settings-project.json').read_text(encoding='utf-8'))
assert project['project']['seed'] == 3 and type(project['project']['seed']) is int
assert project['project']['minY'] == 2 and project['project']['maxY'] == 9
assert project['noise2D'][0]['id'] == 'committed'
print('General settings layout, exact private text, preference/project retry and shared capture replay PASS')
