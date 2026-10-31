"""Deferred independent bookmark modal geometry, request/field continuity and exact project replay."""
from pathlib import Path
import copy
import json
import math
import sys
import uuid

out = Path(sys.argv[1])
report = json.loads((out / 'bookmark-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('escapeCancelled', 'heldSuccessorsBlocked', 'boundedFeedback', 'navigationRetry',
            'callbackSuccessorsPreserved', 'unrelatedEditsMergeable', 'staleTargetBlocked',
            'nativeCorrection', 'oneUndoRedoPerEdit', 'projectPrivatePreserved'):
    assert report[key] is True, key
assert report['navigations'] == 2
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms, tabs = ('Add', 'Rename', 'Teleport'), ('noise2D', 'noise3D', 'world')
expected_cases = {(f, t, 'Fields', i) for f in forms for t in tabs for i in range(6)}
expected_cases |= {('Add', 'world', 'Feedback', i) for i in range(6)}
assert len(report['cases']) == 60
assert {(c['form'], c['tab'], c['page'], c['scenario']) for c in report['cases']} == expected_cases
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
identities = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i = case['scenario']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['work'], case['modal'])
    contains(case['modal'], case['body'])
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == {'apply', 'cancel', 'fields', 'feedback'}
    assert len(set(case['ids'].values())) == 4 and all(case['ids'].values())
    previous_bottom = case['body']['y'] + case['body']['height']
    for row in (('apply', 'cancel'), ('fields', 'feedback')):
        for key in row:
            contains(case['modal'], boxes[key])
            assert boxes[key]['y'] >= previous_bottom - .1
        left, right = row
        assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
        assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
        previous_bottom = max(boxes[key]['y'] + boxes[key]['height'] for key in row)
    fields = set() if case['form'] == 'Rename' else {'X', 'Z'}
    if case['tab'] != 'noise2D' and case['form'] != 'Rename':
        fields.add('Y')
    if case['form'] != 'Teleport':
        fields.add('Name')
    assert set(case['fields']) == fields
    assert len(set(case['fields'].values())) == len(fields) and all(case['fields'].values())
    assert case['request'] > 0
    key = (case['form'], case['tab'], case['page'])
    identity = (case['request'], case['ids'], case['fields'])
    if key not in identities:
        identities[key] = identity
    assert identities[key] == identity

names = ('before', 'added', 'renamed')
before, added, renamed = [json.loads((out / (n + '.json')).read_text(encoding='utf-8')) for n in names]
for previous, current in zip(names, names[1:]):
    assert json.loads((out / (current + '-undone.json')).read_text(encoding='utf-8')) == json.loads((out / (previous + '.json')).read_text(encoding='utf-8'))
    assert json.loads((out / (current + '-redone.json')).read_text(encoding='utf-8')) == json.loads((out / (current + '.json')).read_text(encoding='utf-8'))
for tab in tabs:
    bookmark, = before['bookmarks'][tab]
    assert bookmark['name'] == 'original-' + tab and bookmark['x'] == -3 and bookmark['z'] == -7
    assert bookmark.get('y') == (None if tab == 'noise2D' else 5)
expected = copy.deepcopy(before)
new_bookmark = added['bookmarks']['noise2D'][-1]
assert set(new_bookmark) <= {'uuid', 'name', 'x', 'y', 'z'}
assert new_bookmark['name'] == 'private-noise2D' and new_bookmark['x'] == 17 and new_bookmark.get('y') is None and new_bookmark['z'] == -7
uuid.UUID(new_bookmark['uuid'])
assert new_bookmark['uuid'] not in {b['uuid'] for values in before['bookmarks'].values() for b in values}
expected['bookmarks']['noise2D'].append(new_bookmark)
assert added == expected
expected['bookmarks']['world'][0]['name'] = 'successor-world'
assert renamed == expected
assert before['project']['seed'] == 0 and len(before['noise2D']) == 1
assert before['noise2D'][0]['id'] == 'existing' and before['noise2D'][0]['seedOffset'] == 0
assert before['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not before['worldGenerator']['layers'] and not before['noise3D']
assert json.loads((out / 'preferences.json').read_text(encoding='utf-8')) == json.loads((out / 'preferences-before.json').read_text(encoding='utf-8'))
print('Bookmark modal geometry, native identity continuity and exact project/Undo replay PASS')
