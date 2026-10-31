"""Deferred independent Help/About/context geometry, exact copies and history replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('manual-decisions-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['runtimeSource'] == 'fixture'
for key in ('escapePreserved', 'heldHelpOwnersBlocked', 'queryCacheIndependent',
            'historyRoundTripScoped', 'privateMetadataIndependent', 'publishedMetadataScoped',
            'aboutSectionInstanceScoped', 'aboutIndependent', 'contextSourcesScoped',
            'callbackRetriesContained', 'successorsPreserved', 'hiddenContextsReleased',
            'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalClipboardVerified', 'physicalDpiVerified', 'physicalDetachedVerified',
            'gpuFontTextureVerified', 'actualRuntimeGlVerified'):
    assert report[key] is False, key
orders = {'Topics': (('copy', 'page'), ('back', 'clear')),
          'Guidance': (('copy', 'page'), ('back', 'clear')),
          'Runtime': (('copy', 'help'),), 'Licenses': (('copy', 'help'),),
          'Context': (('open',),)}
assert len(report['cases']) == 30
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, i) for f in orders for i in range(6)}
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
identities = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i, form = case['scenario'], case['form']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['window'], case['body'])
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == {k for row in orders[form] for k in row}
    assert len(set(case['ids'].values())) == len(boxes) and all(case['ids'].values())
    for row in orders[form]:
        for key in row:
            contains(case['window'], boxes[key])
            assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    if form in ('Topics', 'Guidance'):
        assert boxes['copy']['y'] + boxes['copy']['height'] <= boxes['back']['y'] + .1
    if form not in identities:
        identities[form] = case['ids']
    assert identities[form] == case['ids']
assert identities['Topics'] != identities['Guidance']
assert identities['Runtime'] != identities['Licenses']
texts = [(out / name).read_text(encoding='utf-8') for name in ('topic-a.txt', 'topic-b.txt', 'runtime.txt', 'licenses.txt')]
assert report['copied'] == texts
topic_a, topic_b, runtime, licenses = texts
for text, publication in ((topic_a, 'A'), (topic_b, 'B')):
    assert 'Topic: noise2D.algorithm\n' in text
    assert ('publication ' + publication + ' ### 雪\n' + 'complete guidance 🌋 ' * 400) in text
    assert 'CONSTANT — Flat snow 雪' in text
    assert 'unpublished private guidance' not in text
assert topic_a != topic_b
assert 'Application: Procedural Voxel Creator fixture-app\n' in runtime
assert 'Engine: Noise Generator Engine fixture-engine\n' in runtime
assert 'OpenGL renderer: fixture renderer 雪\n' + 'detail ' * 800 in runtime
assert licenses.startswith('No open-source license is granted for project-owned source.')
assert 'JUnit / 5.11.4 / EPL 2.0 / Build/test only' in licenses
assert 'when bundled' in licenses and 'docs/third-party' in licenses
assert report['routes'] == ['help.find', 'help.about']
assert report['finalTopic'] == 'noise' and report['finalPage'] == 'Topics'
events = read('manual-events.json')
entries, counts = events['entries'], events['counts']
assert counts == dict(retained=4, unread=4, actionRequired=4, evicted=0)
assert len(entries) == 4
assert {(e['code'], e['message']) for e in entries} == {
    ('HELP_COPY_FAILED', 'help copy boundary'), ('HELP_COPY_FAILED', 'copy successor boundary'),
    ('ABOUT_COPY_FAILED', 'java.lang.IllegalStateException'), ('ABOUT_HELP_FAILED', 'about help boundary')}
for event in entries:
    assert event['count'] == 1 and event['severity'] == 'ERROR' and event['subsystem'] == 'APPLICATION'
    assert not event['read'] and not event['dismissed'] and event['actionRequired'] and event['target'] is None
project = read('unchanged-project.json')
assert project == read('protected-project.json')
assert read('preferences.json') == read('preferences-before.json')
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['noise3D'] and not project['worldGenerator']['layers']
print('Manual footer geometry, exact publication/runtime/license copies, context routes and unchanged project/preferences replay PASS')
