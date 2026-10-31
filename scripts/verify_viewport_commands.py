"""Deferred independent viewport command geometry, routing and persistence isolation replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('viewport-commands-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'toolbarSourceScoped', 'completeReviewRechecked', 'hiddenTabReopenScoped',
            'menuDocumentPickScoped', 'callbacksBalanced', 'successorsPreserved', 'autoSaveAtomic', 'privatePreserved'):
    assert report[key] is True, key
assert report['physicalDpiVerified'] is False and report['gpuGenerationVerified'] is False
assert len(report['cases']) == 36
assert {(c['tab'], c['form'], c['scenario']) for c in report['cases']} == {
    (tab, form, i) for tab in range(3) for form in ('toolbar', 'menu') for i in range(6)}
two_d = ['GRAYSCALE', 'HEATMAP', 'CONTOUR', 'THRESHOLD', 'HISTOGRAM', 'GRID', 'TELEPORT', 'ADD_BOOKMARK', 'REGENERATE']
common = ['TELEPORT', 'ADD_BOOKMARK', 'REGENERATE']


def contains(outer, inner):
    for rect in (outer, inner):
        assert all(math.isfinite(value) for value in rect.values())
        assert rect['width'] > 0 and rect['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1


ids = {}
for case in report['cases']:
    tab, form, i = case['tab'], case['form'], case['scenario']
    assert case['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert case['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    keys = ['REGENERATE', 'AUTO'] if form == 'toolbar' else two_d if tab == 0 else common
    assert set(case['ids']) == set(case['actions']) == set(keys)
    assert all(case['ids'].values()) and len(set(case['ids'].values())) == len(keys)
    if form == 'toolbar':
        for box in case['actions'].values():
            contains(case['window'], box)
        a, b = (case['actions'][key] for key in keys)
        assert (a['x'] + a['width'] <= b['x'] + 1.1 or a['y'] + a['height'] <= b['y'] + 1.1)
    else:
        contains(case['popup'], case['list'])
        # Rows can extend outside the visible scrolling child at large synthetic font scales.
        for box in case['actions'].values():
            assert all(math.isfinite(value) for value in box.values())
            assert box['width'] > 0 and box['height'] > 0
        for a, b in zip(keys, keys[1:]):
            assert case['actions'][a]['y'] + case['actions'][a]['height'] <= case['actions'][b]['y'] + 1.1
    key = tab, form
    if key not in ids:
        ids[key] = case['ids']
    assert ids[key] == case['ids']
assert len({ids[tab, 'toolbar']['REGENERATE'] for tab in range(3)}) == 3
expected = [dict(tab=tab, command='REGENERATE', picked=None) for tab in range(3)]
for tab in range(3):
    for command in two_d if tab == 0 else common:
        expected.append(dict(tab=tab, command=command, picked=(dict(x=7 if tab == 0 else 17 if tab == 1 else 27,
            y=None if tab == 0 else 18 if tab == 1 else 28, z=-9 if tab == 0 else 19 if tab == 1 else 29)
            if command == 'ADD_BOOKMARK' else None)))
expected += [dict(tab=0, command=command, picked=None) for command in ('GRID', 'AUTO', 'AUTO', 'REGENERATE')]
assert report['deliveries'] == expected and len(expected) == 22 and report['regenerations'] == 7
events = read('viewport-events.json')
assert events['counts'] == dict(retained=4, unread=4, actionRequired=4, evicted=0)
assert {e['message'] for e in events['entries']} == {
    'Could not save preferences', 'viewport route boundary', 'viewport successor dialog', 'viewport successor menu'}
for event in events['entries']:
    assert event['code'] == 'VIEWPORT_COMMAND_FAILED' and event['count'] == 1
    assert event['severity'] == 'ERROR' and event['subsystem'] == 'APPLICATION'
    assert event['actionRequired'] and not event['read'] and not event['dismissed'] and event['target'] is None
assert read('viewport-project.json') == read('after.json')
preferences = read('preferences-before.json')
assert preferences == read('preferences.json')
preferences['editor']['autoRegenerate'] = not preferences['editor']['autoRegenerate']
assert preferences == read('auto-toggled.json')
assert (out / 'occupied' / 'keep').read_text(encoding='utf-8') == 'original'
print('Viewport toolbar/menu geometry, exact source-owned routes, failure recovery and atomic Auto isolation PASS')
