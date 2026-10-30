"""Deferred independent Help/About layout and complete-copy fixture replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'help-about-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
assert report['fontSource'] == 'user-scale-fixture' and report['runtimeSource'] == 'fixture'
for key in ('optionSearch', 'relatedBack', 'completeCopyCallbacks', 'cachedQueries',
            'inspectorContext', 'aboutHelpRoute', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalClipboardVerified', 'physicalDpiVerified', 'physicalDetachedVerified',
            'gpuFontTextureVerified', 'desktopMenuGesturesVerified', 'actualRuntimeGlVerified'):
    assert report[key] is False, key
assert 0 < report['unfilteredRows'] < report['totalTopics']
assert report['unfilteredStride'] == math.ceil(report['unfilteredStride'])
assert report['unfilteredRows'] <= math.ceil(report['unfilteredHeight'] / report['unfilteredStride']) + 2
forms = ('topics', 'guidance', 'runtime', 'licenses')
assert len(report['cases']) == 24
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in forms}
sizes = ((1400, 1200), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 300))
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


row_id = None
for case in report['cases']:
    i, form = case['scenario'], case['form']
    width, height = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=width, height=height)
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    assert case['selected'] == 'noise2D.algorithm'
    contains(case['window'], case['body'])
    contains(case['window'], case['selector'])
    rows = (('copy', 'page'), ('back', 'clear')) if form in ('topics', 'guidance') else (('copy', 'help'),)
    actions = case['actions']
    assert set(actions) == {key for row in rows for key in row}
    for row in rows:
        for key in row:
            contains(case['window'], actions[key])
            assert actions[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
        for left, right in zip(row, row[1:]):
            assert abs(actions[left]['y'] - actions[right]['y']) <= .1
            assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .1
    if form in ('topics', 'guidance'):
        contains(case['window'], case['search'])
        assert actions['copy']['y'] + actions['copy']['height'] <= actions['back']['y'] + .1
        if form == 'topics':
            assert case['rows'] == 1 and set(case['ids']) == {'noise2D.algorithm'}
            assert case['stride'] == math.ceil(case['stride'])
            contains(case['body'], case['results'])
            if row_id is None:
                row_id = case['ids']['noise2D.algorithm']
            assert case['ids']['noise2D.algorithm'] == row_id
        else:
            assert case['rows'] == 0 and case['ids'] == {} and case['results'] is None
contains(report['contextPopup'], report['contextAction'])
assert report['aboutTopic'] == 'help.about'
assert report['inspectorTopic'] == 'noise2D.algorithm.CONSTANT.value'
topic = (out / 'topic.txt').read_text(encoding='utf-8')
runtime = (out / 'runtime.txt').read_text(encoding='utf-8')
licenses = (out / 'licenses.txt').read_text(encoding='utf-8')
assert 'Topic: noise2D.algorithm\n' in topic
assert 'complete ### Unicode 雪\n' + 'full guidance ' * 400 in topic
assert 'CONSTANT — Flat snow 雪' in topic and 'Noise Editor [noise]' in topic
assert 'Application: Procedural Voxel Creator fixture-app\n' in runtime
assert 'Engine: Noise Generator Engine fixture-engine\n' in runtime
assert 'OpenGL renderer: renderer ### snow 雪\n' + 'detail ' * 400 in runtime
assert licenses.startswith('No open-source license is granted for project-owned source.')
assert 'JUnit / 5.11.4 / EPL 2.0 / Build/test only' in licenses
assert 'when bundled' in licenses and 'docs/third-party' in licenses
print('Help/About persistent geometry, clipped topic identity, complete Unicode copy and explicit context routing PASS')
