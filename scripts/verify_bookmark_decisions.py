"""Deferred independent bookmark toolbar/picker geometry, captured target and exact history replay."""
from pathlib import Path
import copy
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('bookmark-decisions-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'heldToolbarScoped', 'dialogOwnershipScoped', 'tabLifetimesIndependent',
            'heldRowsScoped', 'hiddenReopenScoped', 'documentLifetimeScoped', 'navigationRetryScoped',
            'callbacksBalanced', 'successorPreserved', 'captureRechecked', 'latestViewCaptured',
            'updateDeleteHistory', 'explicitDialogsRouted', 'privatePreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert len(report['cases']) == 36
assert {(c['form'], c['tab'], c['scenario']) for c in report['cases']} == {
    (form, tab, i) for form in ('toolbar', 'picker') for tab in ('noise2D', 'noise3D', 'world') for i in range(6)}
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
identities = {}
toolbar_keys = ('picker', 'Add Bookmark', 'Teleport', 'Rename', 'Update Coordinates', 'Delete Bookmark')


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1


for case in report['cases']:
    form, tab, i = case['form'], case['tab'], case['scenario']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    if form == 'toolbar':
        assert set(case['ids']) == set(case['actions']) == set(toolbar_keys)
        for rect in case['actions'].values():
            contains(case['window'], rect)
        boxes = list(case['actions'].values())
        for j, first in enumerate(boxes):
            for second in boxes[j + 1:]:
                assert (first['x'] + first['width'] <= second['x'] + 1.1
                        or second['x'] + second['width'] <= first['x'] + 1.1
                        or first['y'] + first['height'] <= second['y'] + 1.1
                        or second['y'] + second['height'] <= first['y'] + 1.1)
    else:
        contains(case['popup'], case['query'])
        contains(case['popup'], case['list'])
        assert set(case['ids']) == {report['beta'][tab]} and case['rows'] == 1
        assert case['stride'] > 0 and case['stride'] == math.ceil(case['stride'])
    assert all(case['ids'].values()) and len(set(case['ids'].values())) == len(case['ids'])
    key = form, tab
    if key not in identities:
        identities[key] = case['ids']
    assert identities[key] == case['ids']
assert len({identities['toolbar', tab]['Delete Bookmark'] for tab in report['alpha']}) == 3
assert report['jumps'] == [dict(tab='WORLD', coordinate=dict(x=22, y=23, z=-22))]
events = read('bookmark-events.json')
assert events['counts'] == dict(retained=4, unread=4, actionRequired=4, evicted=0)
entries = {(e['code'], e['message']): e for e in events['entries']}
context_message = 'Document or bookmark context changed during coordinate capture; retry.'
expected = {('BOOKMARK_NAVIGATION_FAILED', 'bookmark navigation boundary'): 1,
            ('BOOKMARK_NAVIGATION_FAILED', 'bookmark navigation successor'): 1,
            ('BOOKMARK_UPDATE_FAILED', context_message): 1,
            ('BOOKMARK_DIALOG_FAILED', context_message): 3}
assert set(entries) == set(expected)
for key, count in expected.items():
    event = entries[key]
    assert event['count'] == count and event['severity'] == 'ERROR' and event['subsystem'] == 'APPLICATION'
    assert event['actionRequired'] and not event['read'] and not event['dismissed'] and event['target'] is None
before = read('before.json')
assert before == read('protected-project.json')
assert read('blocked-capture.json') == read('guard-target.json')
assert read('preferences-before.json') == read('preferences.json')
assert before['formatVersion'] == 2 and len(before['noise2D']) == 1
assert before['noise2D'][0]['id'] == 'existing' and before['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
for tab in ('noise2D', 'noise3D', 'world'):
    assert len(before['bookmarks'][tab]) == 96
    assert before['bookmarks'][tab][0]['uuid'] == report['alpha'][tab]
    assert before['bookmarks'][tab][1]['uuid'] == report['beta'][tab]
updated = copy.deepcopy(before)
position = next(i for i, b in enumerate(updated['bookmarks']['world']) if b['uuid'] == report['alpha']['world'])
updated['bookmarks']['world'][position].update(x=51, y=52, z=53)
deleted = copy.deepcopy(updated)
del deleted['bookmarks']['world'][position]
for name, expected in (('updated', updated), ('update-undone', before), ('update-redone', updated),
                       ('deleted', deleted), ('delete-undone', updated), ('delete-redone', deleted)):
    assert read(name + '.json') == expected, name
print('Bookmark toolbar/picker geometry, exact navigation, guarded capture and complete Update/Delete Undo/Redo replay PASS')
