"""Deferred independent Find/Trace footer, immutable clipboard and project/preferences replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('utility-decisions-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'heldFindQueriesBlocked', 'findClearRetainedSelection',
            'heldTraceOwnersBlocked', 'hiddenContextsReleased', 'documentLifetimeScoped',
            'independentTraceHosts', 'pendingQueryScoped', 'copyFailuresContained',
            'emptyCopyBlocked', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalClipboardVerified', 'physicalDpiVerified', 'physicalDetachedVerified',
            'gpuFontTextureVerified'):
    assert report[key] is False, key
orders = {'Results': (('navigate', 'section', 'clear'),),
          'Filters': (('navigate', 'section', 'clear'),),
          'Trace': (('copy-trace', 'voxel', 'control'), ('copy-value', 'previous', 'next'))}
assert len(report['cases']) == 18
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
    assert set(boxes) == set(case['ids']) == {key for row in orders[form] for key in row}
    assert len(set(case['ids'].values())) == len(boxes) and all(case['ids'].values())
    for row in orders[form]:
        for key in row:
            contains(case['window'], boxes[key])
            assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    if form == 'Trace':
        assert boxes['copy-trace']['y'] + boxes['copy-trace']['height'] <= boxes['copy-value']['y'] + .1
    if form not in identities:
        identities[form] = case['ids']
    assert identities[form] == case['ids']
assert identities['Results'] != identities['Filters']
main, second = report['mainHostIds'], report['secondHostIds']
assert set(main) == set(second) == set(identities['Trace'])
assert all(main.values()) and all(second.values())
assert all(main[key] != second[key] for key in main)
reason = 'reason 🌋 ### snow 雪\n' * 1000
assert (out / 'trace-value.txt').read_text(encoding='utf-8') == reason
details = (out / 'trace-details.txt').read_text(encoding='utf-8')
assert report['copied'] == [reason, details]
assert len(reason) > 4096 and details.count(reason) == 2
assert 'Primary field: 22.0' in details and 'Final solid' in details
events = read('utility-events.json')
assert events['counts'] == dict(retained=1, unread=1, actionRequired=1, evicted=0)
assert len(events['entries']) == 1
failure = events['entries'][0]
assert failure['code'] == 'TRACE_ACTION_FAILED' and failure['message'] == 'copy boundary'
assert failure['severity'] == 'ERROR' and failure['subsystem'] == 'APPLICATION'
assert failure['count'] == 2 and not failure['read'] and not failure['dismissed'] and failure['actionRequired']
assert failure['target'] is None
project = read('unchanged-project.json')
assert project == read('protected-project.json')
assert read('preferences.json') == read('preferences-before.json')
assert project['formatVersion'] == 2 and [n['id'] for n in project['noise2D']] == ['field', 'needle']
assert project['noise2D'][1]['algorithm']['parameters'] == {'value': .125}
assert project['noise2D'][0]['seedOffset'] == 0
assert len(project['voxelTypes']) == 1 and project['voxelTypes'][0]['id'] == 'solid'
assert len(project['worldGenerator']['layers']) == 1
assert len(project['worldGenerator']['layers'][0]['operations']) == 2
print('Find/Trace footer geometry, separate native hosts, exact immutable copies and unchanged project/preferences replay PASS')
