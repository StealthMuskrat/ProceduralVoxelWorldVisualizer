"""Deferred independent history completion routes, native geometry and exact persisted deltas."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('project-history-completion-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['movementFence'] is True and report['callbacksAfterPopup'] == 13
expected = [('undo', 1, 'base', 1, False, 0), ('redo', 1, 'edited', 1, False, 0),
            ('refresh-failure', 1, 'base', 1, False, 1),
            ('decision-failure', 0, 'edited', 0, True, 2),
            ('decision-mutation', 0, 'edited', 0, True, 2),
            ('decision-successor', 0, 'edited', 0, True, 2),
            ('request-successor', 0, 'edited', 0, True, 2),
            ('request-failure', 0, 'edited', 0, True, 3),
            ('escape', 0, 'edited', 0, True, 3), ('held', 0, 'edited', 0, True, 3)]
assert len(report['routes']) == len(expected)
for row, (phase, revision, canonical, refreshes, private, errors) in zip(report['routes'], expected):
    assert row == dict(phase=phase, revisionDelta=revision, canonical=canonical, refreshes=refreshes,
                       privateRetained=private, pending=False, errorCount=errors)
errors = report['errors']
assert [row['code'] for row in errors] == ['PROJECT_HISTORY_REFRESH_FAILED', 'PROJECT_HISTORY_GUARD_FAILED', 'PROJECT_HISTORY_GUARD_FAILED']
assert [row['message'] for row in errors] == ['Undo completed; UI refresh failed: history refresh boundary',
        'Project history decision failed: history decision boundary',
        'Project history request failed: history request boundary']
assert all(row['count'] == 1 and row['severity'] == 'ERROR' and row['subsystem'] == 'PROJECT'
           and row['actionRequired'] is True for row in errors)
cases = report['cases']
assert [(row['phase'], row['scenario']) for row in cases] == [(phase, i) for phase in ('undo', 'redo') for i in range(6)]
ids = {}
for row in cases:
    phase, i = row['phase'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert set(row['actions']) == set(row['ids']) == {'DISCARD', 'CANCEL'}
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(phase, row['ids'])
    assert ids[phase] == row['ids']
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
base = read('source-base.json')
edited = deepcopy(base)
edited['noise2D'][0]['algorithm']['parameters']['value'] = 4
assert read('source-edited.json') == edited and read('after.json') == edited
for phase in ('undo', 'redo', 'refresh-failure'):
    forward = phase == 'redo'
    assert read(phase + '.json') == (edited if forward else base)
    trace = read(phase + '-trace.json')
    assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
    assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
    expected_field = ((4 if forward else 2) + 2 * (2 + 1) + 3) * 2
    assert expected_field == (26 if forward else 22)
    assert all(step['field'] == expected_field for step in trace['steps'])
assert read('preferences-before.json') == read('preferences.json')
print('History post-popup completion, ten routes, successor ownership, accepted move fence, exact Undo/Redo deltas and field-22/26 expectations PASS')
