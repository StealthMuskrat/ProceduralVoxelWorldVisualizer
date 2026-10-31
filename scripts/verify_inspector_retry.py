"""Deferred independent exact recovery target, native presentation and persisted-value checks."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('inspector-retry-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False and report['backgroundRetryVerified'] is False
for key in ('escapePreserved', 'attemptScoped', 'pendingScoped', 'canonicalScoped', 'documentScoped',
            'rawRoundTripScoped', 'postDrawRechecked', 'hiddenScoped', 'privatePreserved'):
    assert report[key] is True, key
cases = report['cases']
assert len(cases) == 12
assert [(row['page'], row['scenario']) for row in cases] == [(page, i) for page in ('Coordinates', 'Trace') for i in range(6)]
ids = {}
for row in cases:
    page, i = row['page'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert set(row['actions']) == set(row['ids']) == {'trace-coordinate', 'trace-cancel', 'trace-bookmark', 'trace-restore', 'trace-refresh', 'trace-page'}
    assert all(value != 0 for value in row['ids'].values()) and row['retrySerial'] > 0
    if page not in ids:
        ids[page] = row['ids']
    assert ids[page] == row['ids']
    for box in row['actions'].values():
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
assert ids['Coordinates']['trace-refresh'] != ids['Trace']['trace-refresh']
deliveries = report['deliveries']
assert len(deliveries) == 2 and deliveries[0]['serial'] < deliveries[1]['serial']
for i, row in enumerate(deliveries):
    assert row['target'] == dict(x=7, y=0, z=3) and row['entered'] == dict(x=-17, y=0, z=3)
    assert row['displayed'] == ([] if i == 0 else [2, 0, 3])
    assert row['field'] == (2 + 2 * (7 + 1) + 3) * 2 == 42 and row['finalType'] == 1
assert read('retry-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
trace = read('retry-result.json')
assert trace['position'] == dict(x=7, y=0, z=3) and trace['finalType'] == 1
assert len(trace['steps']) == 2 and all(step['field'] == 42 for step in trace['steps'])
print('Inspector Retry exact failed target, owned native actions, no-result recovery and full project/preferences isolation PASS')
