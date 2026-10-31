"""Deferred independent Find/reference eligibility routes, exact edits and native modal receipts."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('navigation-eligibility-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['privateEditorPreserved'] is True and report['oneUndoRestored'] is True
expected = [('find-rejected', 0, True, False), ('reference-rejected', 0, True, False),
            ('find-blocked-apply', 0, True, True), ('find-blocked-discard', 0, True, True),
            ('find-blocked-cancel', 0, True, True), ('find-apply', 1, False, False),
            ('find-discard', 0, False, False), ('reference-apply', 1, False, False),
            ('reference-discard', 0, False, False)]
assert len(report['routes']) == len(expected)
for row, (phase, revision, raw, private) in zip(report['routes'], expected):
    assert row == dict(phase=phase, revisionDelta=revision, rawRetained=raw,
                       privateEditor=private, pending=False)
cases = report['cases']
assert [(row['form'], row['scenario']) for row in cases] == [(form, i) for form in ('find', 'reference') for i in range(6)]
ids = {}
for row in cases:
    form, i = row['form'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert set(row['actions']) == set(row['ids']) == {'APPLY', 'DISCARD', 'CANCEL'}
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(form, row['ids'])
    assert ids[form] == row['ids']
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
source = read('source-project.json')
expected_project = deepcopy(source)
expected_project['noise2D'][0]['id'] = 'private-field'
for form in ('find', 'reference'):
    assert read(form + '-applied.json') == expected_project
    assert read(form + '-undo.json') == source
    trace = read(form + '-trace.json')
    assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
    assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
    assert all(step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22 for step in trace['steps'])
assert read('after.json') == source
assert read('preferences-before.json') == read('preferences.json')
print('Find/reference private-editor eligibility, nine native routes, modal geometry, exact source edits and complete Undo/preferences replay PASS')
