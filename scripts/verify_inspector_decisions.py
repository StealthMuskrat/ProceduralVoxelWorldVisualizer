"""Deferred independent Inspector footer geometry, exact decision routing and project replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('inspector-decisions-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['traceSource'] == 'synthetic-presentation'
for key in ('escapePreserved', 'heldInputOwnersBlocked', 'requestIdentityScoped',
            'snapshotOwnersScoped', 'sameFrameRechecked', 'disabledActionsBlocked',
            'documentLifetimeScoped', 'hiddenContextsReleased', 'instanceScopesDistinct',
            'feedbackIndependent', 'invalidDisplayRejected', 'explicitActionsRouted',
            'foreignRefreshBlocked', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
orders = (('trace-coordinate', 'trace-cancel', 'trace-bookmark'),
          ('trace-restore', 'trace-refresh', 'trace-page'))
assert len(report['cases']) == 12
assert {(c['form'], c['scenario']) for c in report['cases']} == {(f, i) for f in ('Coordinates', 'Trace') for i in range(6)}
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
identities, field_ids = {}, None


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
    assert set(boxes) == set(case['ids']) == {k for row in orders for k in row}
    assert len(set(case['ids'].values())) == len(boxes) and all(case['ids'].values())
    for row in orders:
        for key in row:
            contains(case['window'], boxes[key])
            assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    assert boxes['trace-coordinate']['y'] + boxes['trace-coordinate']['height'] <= boxes['trace-restore']['y'] + .1
    if form not in identities:
        identities[form] = case['ids']
    assert identities[form] == case['ids']
    if form == 'Coordinates':
        assert set(case['fieldIds']) == {'X', 'Y', 'Z'} and all(case['fieldIds'].values())
        if field_ids is None:
            field_ids = case['fieldIds']
        assert field_ids == case['fieldIds']
    else:
        assert case['fieldIds'] == {}
assert identities['Coordinates'] != identities['Trace']
deliveries = report['delivered']
assert [d['action'] for d in deliveries] == ['CANCEL', 'TRACE', 'BOOKMARK', 'CANCEL',
                                            'RESTORE', 'REFRESH', 'CANCEL', 'CANCEL']
entered = dict(x=-(2**31), y=0, z=3)
traced = dict(x=2, y=0, z=3)
assert [d['coordinate'] for d in deliveries] == [dict(x=-17, y=0, z=3), entered, entered,
                                               entered, traced, traced, traced, None]
assert [d['pick'] for d in deliveries] == [False] * 7 + [True]
assert deliveries[0]['request'] > 0
assert deliveries[1]['request'] == deliveries[4]['request'] == deliveries[5]['request'] == 0
assert deliveries[2]['request'] == deliveries[3]['request'] > deliveries[0]['request']
assert deliveries[7]['request'] > deliveries[6]['request'] > deliveries[3]['request']
assert report['rejected'] == [dict(action='TRACE', message='Displayed X differs from the Inspector input; correct it before continuing')]
assert report['traceStarts'] == 2 and report['bookmarks'] == 1 and report['workersRan'] == 0
assert report['finalCoordinate'] == dict(x=7, y=8, z=9) and report['finalPage'] == 'Coordinates'
project = read('unchanged-project.json')
assert project == read('protected-project.json')
assert read('preferences.json') == read('preferences-before.json')
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'field' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': 2}
assert len(project['worldGenerator']['layers']) == 1
assert len(project['worldGenerator']['layers'][0]['operations']) == 2
trace = read('reviewed-trace.json')
assert trace['position'] == traced and trace['finalType'] == 1 and len(trace['steps']) == 1
assert trace['steps'][0]['field'] == 22 and trace['steps'][0]['action'] == 'SET' and trace['steps'][0]['result'] == 1
print('Inspector footer geometry, exact request/coordinate routing, raw display rejection and unchanged project/preferences replay PASS')
