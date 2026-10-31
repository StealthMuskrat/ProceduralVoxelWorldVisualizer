"""Deferred independent source state routes, native footer geometry and definition reuse checks."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('trace-source-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
for key in ('definitionReuse', 'privateAvailabilityLive', 'heldSourceRejected', 'disabledSourcesInactive',
            'sameResourceDraftPreserved', 'historicalCopyPreserved', 'privatePreserved'):
    assert report[key] is True, key
expected = [('current', 'AVAILABLE', 'AVAILABLE'),
            ('private-removed', 'AVAILABLE', 'CONTROL_UNAVAILABLE'),
            ('private-restored', 'AVAILABLE', 'AVAILABLE'),
            ('renamed', 'SOURCE_CHANGED', 'AVAILABLE'),
            ('undone', 'AVAILABLE', 'AVAILABLE'),
            ('deleted', 'MISSING_SOURCE', 'AVAILABLE'),
            ('restored', 'AVAILABLE', 'AVAILABLE'),
            ('reopened', 'OTHER_DOCUMENT', 'OTHER_DOCUMENT')]
states = report['states']
assert len(states) == len(expected)
for row, (phase, voxel, control) in zip(states, expected):
    assert row['phase'] == phase and row['sources'] == dict(voxel=voxel, control=control)
    assert row['definitionChecks'] >= 2 and 0 <= row['retained'] <= 32
assert states[0]['definitionChecks'] == states[1]['definitionChecks'] == states[2]['definitionChecks']
for i in range(3, 7):
    assert states[i]['definitionChecks'] > states[i - 1]['definitionChecks']
assert states[7]['definitionChecks'] == states[6]['definitionChecks'] and states[7]['retained'] == 0
cases = report['cases']
assert len(cases) == 12
assert [(row['phase'], row['scenario']) for row in cases] == [(phase, i) for phase in ('current', 'changed') for i in range(6)]
keys = {'copy-trace', 'voxel', 'control', 'copy-value', 'previous', 'next'}
ids = {}
for row in cases:
    phase, i = row['phase'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert set(row['actions']) == set(row['ids']) == keys
    assert all(value != 0 for value in row['ids'].values())
    assert row['sources'] == dict(voxel='AVAILABLE' if phase == 'current' else 'SOURCE_CHANGED', control='AVAILABLE')
    if phase not in ids:
        ids[phase] = row['ids']
    assert row['ids'] == ids[phase]
    for box in row['actions'].values():
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
assert ids['current']['voxel'] != ids['changed']['voxel']
assert read('source-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
trace = read('source-trace.json')
assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
assert all(step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22 for step in trace['steps'])
assert 'Compare threshold: 22.0 >= 30.0 : rejected' in (out / 'source-details.txt').read_text(encoding='utf-8')
print('Trace source eligibility, live private controls, bounded definition reuse, native footer and complete project/preferences replay PASS')
