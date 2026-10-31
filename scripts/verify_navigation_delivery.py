"""Deferred independent shared-modal delivery, geometry and complete persistence observations."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('navigation-delivery-native.json')
assert report['formatVersion'] == 1
assert report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert all(report[key] is True for key in ('callbacksAfterPopup', 'scopeRestored', 'unchangedDocument'))
expected = [('retained', 2, 0, 'CANCEL', False, False),
            ('terminal', 1, 0, 'APPLY', False, False),
            ('callback-throw', 2, 1, 'CANCEL', False, False),
            ('explanation-throw', 1, 1, 'CANCEL', False, False),
            ('successor-terminal', 2, 0, 'CANCEL', True, False),
            ('successor-retained', 2, 0, 'CANCEL', True, False),
            ('explicit-close', 1, 0, 'APPLY', False, False),
            ('nested', 1, 0, 'APPLY', False, True),
            ('escape', 1, 0, 'CANCEL', False, False),
            ('held', 1, 0, 'CANCEL', False, False)]
assert len(report['routes']) == len(expected)
for row, (phase, calls, failures, last, successor, nested) in zip(report['routes'], expected):
    assert row == dict(phase=phase, calls=calls, failures=failures, last=last,
                       successor=successor, nested=nested)
cases = report['cases']
assert [(row['phase'], row['scenario']) for row in cases] == [(phase, i) for phase in ('retained', 'terminal') for i in range(6)]
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
assert ids['retained'] != ids['terminal']
assert read('source-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
trace = read('source-trace.json')
assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
assert all(step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22 for step in trace['steps'])
print('Shared modal post-popup delivery, ten routes, native geometry, successor ownership and complete project/preferences replay PASS')
