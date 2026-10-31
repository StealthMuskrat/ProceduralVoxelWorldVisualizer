"""Deferred independent diagnostic footer geometry, exact payload and history-state replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('diagnostic-decisions-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'heldSelectionsBlocked', 'unrelatedPublicationsIndependent',
            'heldBulkViewBlocked', 'heldOccurrencesBlocked', 'validationOwnersScoped',
            'callbackFailuresContained', 'hiddenContextsReleased', 'documentLifetimeScoped',
            'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalClipboardVerified', 'physicalDpiVerified', 'physicalDetachedVerified',
            'gpuFontTextureVerified'):
    assert report[key] is False, key
orders = {'Logs': (('copy', 'navigate', 'show'),),
          'Notifications': (('all-read', 'logs', 'copy'), ('navigate', 'read', 'acknowledge')),
          'Validation': (('validate', 'copy', 'navigate'),)}
assert len(report['cases']) == 18
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, i) for f in orders for i in range(6)}
sizes = ((1200, 900), (360, 640), (360, 640), (360, 640), (720, 640), (1000, 320))
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
ids = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i, form = case['scenario'], case['form']
    width, height = sizes[i]
    assert case['window'] == dict(x=16, y=16, width=width, height=height)
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
    if form == 'Notifications':
        assert boxes['all-read']['y'] + boxes['all-read']['height'] <= boxes['navigate']['y'] + .1
    if form not in ids:
        ids[form] = case['ids']
    assert ids[form] == case['ids']

view = read('diagnostics-view.json')
entries, counts = view['entries'], view['counts']
assert counts['retained'] == len(entries) == 9 and counts['evicted'] == 0
assert counts['unread'] == sum(not e['dismissed'] and not e['read'] for e in entries) == 4
assert counts['actionRequired'] == sum(e['actionRequired'] for e in entries) == 4
by_code = {e['code']: e for e in entries}
assert len(by_code) == len(entries)
assert set(by_code) == {'ALPHA', 'BETA', 'UNRELATED_X', 'UNRELATED_Y', 'UNRELATED_Z',
                        'PROJECT_VALID', 'DIAGNOSTIC_COPY_FAILED', 'DIAGNOSTIC_NAVIGATION_FAILED',
                        'DIAGNOSTIC_LOGS_FAILED'}
alpha, beta = by_code['ALPHA'], by_code['BETA']
assert alpha['id'] == report['alpha'] and beta['id'] == report['beta'] != alpha['id']
assert alpha['count'] == 3 and alpha['read'] and alpha['dismissed'] and not alpha['actionRequired']
assert beta['count'] == 1 and beta['read'] and not beta['dismissed'] and beta['actionRequired']
for code in ('UNRELATED_X', 'UNRELATED_Y', 'UNRELATED_Z'):
    e = by_code[code]
    assert e['count'] == 1 and e['read'] and not e['dismissed'] and not e['actionRequired']
for code, detail in (('DIAGNOSTIC_COPY_FAILED', 'copy boundary'),
                     ('DIAGNOSTIC_NAVIGATION_FAILED', 'navigation boundary'),
                     ('DIAGNOSTIC_LOGS_FAILED', 'IllegalStateException')):
    e = by_code[code]
    assert e['count'] == 1 and e['severity'] == 'ERROR' and e['subsystem'] == 'APPLICATION'
    assert e['message'] == detail and not e['read'] and not e['dismissed'] and e['actionRequired']
    assert e['target'] is None
valid = by_code['PROJECT_VALID']
assert valid['count'] == 2 and not valid['read'] and not valid['dismissed'] and not valid['actionRequired']
assert len(report['copied']) == 4 and report['copied'][0] == report['copied'][1]
for text in report['copied'][:2]:
    assert alpha['message'] in text and 'Occurrences: 1' in text and 'Read: false / Action required: true' in text
assert 'OTHER_ROW' in report['copied'][2] and '/project/maxY' in report['copied'][2]
assert alpha['message'] in report['copied'][3] and 'Occurrences: 3' in report['copied'][3]
assert 'Read: true / Action required: false' in report['copied'][3]
assert report['openedLogs'] == 1 and len(report['navigated']) == 2
project = read('unchanged-project.json')
assert project == read('protected-project.json')
assert read('preferences.json') == read('preferences-before.json')
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
noise = project['noise2D'][0]
assert noise['id'] == 'existing' and noise['seedOffset'] == 0
assert noise['algorithm']['parameters'] == {'value': .125}
assert not project['noise3D'] and not project['worldGenerator']['layers']
assert alpha['target']['project'] == beta['target']['project'] == project['project']['uuid']
assert alpha['target']['resource'] == noise['uuid'] and alpha['target']['pointer'] == '/noise2D/0'
assert not alpha['target']['settings']
assert beta['target']['settings'] and beta['target']['resource'] is None
assert beta['target']['pointer'] == '/project/seed'
settings, source = report['navigated']
assert settings['project'] == project['project']['uuid'] and settings['settings']
assert settings['resource'] is None and settings['pointer'] == '/project/maxY'
assert source == alpha['target']
print('Diagnostic footer geometry, exact callback payloads, independent entry/view owners and retained history replay PASS')
