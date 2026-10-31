"""Deferred independent optional trace footer layout, exact intent routes and isolation checks."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('trace-inspect-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False and report['workersVerified'] is False
for key in ('escapePreserved', 'documentScoped', 'snapshotScoped', 'canonicalIdentityScoped',
            'hiddenHostScoped', 'foreignDisabled', 'failedIntentConsumed', 'successorPreserved',
            'callbacksBalanced', 'privatePreserved'):
    assert report[key] is True, key
cases = report['cases']
assert len(cases) == 12
assert [(row['phase'], row['scenario']) for row in cases] == [(phase, i) for phase in ('current', 'reopened') for i in range(6)]
keys = {'copy-trace', 'voxel', 'control', 'copy-value', 'previous', 'next', 'inspect'}
ids = {}
for row in cases:
    phase, i = row['phase'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert row['reopened'] is (phase == 'reopened')
    assert set(row['actions']) == set(row['ids']) == keys
    assert all(row['ids'][key] != 0 for key in keys)
    if phase not in ids:
        ids[phase] = row['ids']
    assert row['ids'] == ids[phase]
    window = row['window']
    for action in row['actions'].values():
        assert all(math.isfinite(v) for v in action.values())
        assert action['width'] > 0 and action['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert action[axis] >= window[axis] - 1.1
            assert action[axis] + action[size] <= window[axis] + window[size] + 1.1
    inspect = row['actions']['inspect']
    assert inspect['y'] > max(row['actions'][key]['y'] for key in keys - {'inspect'})
assert ids['current']['inspect'] != ids['reopened']['inspect']
deliveries = report['deliveries']
assert len(deliveries) == 4
for i, delivery in enumerate(deliveries):
    assert delivery['attempt'] == i + 1 and delivery['position'] == dict(x=2, y=0, z=3)
    assert delivery['current'] is True and delivery['reopened'] is (i < 3)
assert len({row['revision'] for row in deliveries}) == 1
events = read('trace-inspect-events.json')['entries']
assert len(events) == 2
assert {entry['message'] for entry in events} == {'trace inspect boundary', 'trace inspect successor'}
for entry in events:
    assert entry['code'] == 'TRACE_ACTION_FAILED' and entry['severity'] == 'ERROR' and entry['subsystem'] == 'APPLICATION'
    assert entry['count'] == 1 and entry['actionRequired'] is True
assert read('trace-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
print('Trace In Inspector footer geometry, consumed exact intents, failure/successor handling and complete project/preferences isolation PASS')
