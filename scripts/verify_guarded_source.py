"""Deferred independent guarded-source routes, geometry, persistence and trace expectations."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('guarded-source-native.json')
assert report['formatVersion'] == 1
assert report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['popupScopesRestored'] is True and report['oneUndoRestored'] is True
assert report['callbacksAfterPopup'] == 13
expected = [('child', 1, 'layer', 3, 0, 0), ('unchanged', 1, 'noise', 3, 1, 0),
            ('precheck', 0, 'layer', 2, 0, 1), ('postcheck', 1, 'layer', 3, 0, 2),
            ('reveal', 1, 'noise', 3, 2, 3), ('successor', 1, 'layer', 3, 0, 3)]
assert len(report['phases']) == len(expected)
for row, (phase, revision, selected, checks, reveals, errors) in zip(report['phases'], expected):
    assert row == dict(phase=phase, revisionDelta=revision, selected=selected, checks=checks,
                       reveals=reveals, pending=False, errorCount=errors)
errors = report['errors']
assert [entry['code'] for entry in errors] == ['SOURCE_NAVIGATION_FAILED', 'SOURCE_NAVIGATION_FAILED', 'SOURCE_REVEAL_FAILED']
assert [entry['message'] for entry in errors] == ['source check before apply',
        'Draft applied; source navigation failed: source check after apply', 'source reveal boundary']
assert all(entry['count'] == 1 and entry['severity'] == 'ERROR' and
           entry['subsystem'] == 'APPLICATION' and entry['actionRequired'] is True for entry in errors)
cases = report['cases']
assert [(row['phase'], row['scenario']) for row in cases] == [(phase, i) for phase in ('child', 'unchanged') for i in range(6)]
ids = {}
for row in cases:
    phase, i = row['phase'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert set(row['actions']) == set(row['ids']) == {'APPLY', 'DISCARD', 'CANCEL'}
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(phase, row['ids'])
    assert ids[phase] == row['ids']
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
source = read('source-project.json')
child = deepcopy(source)
child['worldGenerator']['layers'][0]['operations'][-1]['overrides']['threshold'] = 20
renamed = deepcopy(source)
renamed['worldGenerator']['layers'][0]['id'] = 'applied-layer'
for phase, *_ in expected:
    assert read(phase + '-after.json') == (child if phase == 'child' else source if phase == 'precheck' else renamed)
    trace = read(phase + '-trace.json')
    assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
    assert [step['action'] for step in trace['steps']] == ['SET', 'SET' if phase == 'child' else 'NO_CHANGE']
    assert all(step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22 for step in trace['steps'])
assert read('child-undo.json') == source
assert read('preferences-before.json') == read('preferences.json')
print('Guarded source completion, six routes, single commit, native modal scopes, exact project transformations and field-22 trace expectations PASS')
