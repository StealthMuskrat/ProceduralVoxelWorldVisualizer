"""Deferred independent named-modal font composition, geometry and persistence checks."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('dialog-owner-scale-native.json')
assert report['formatVersion'] == 1
assert report['fontSource'] == 'synthetic-dpi-and-user-scale-fixture'
assert report['privatePreserved'] is True
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False
owners = ('Properties', 'Find', 'Resources', 'Visualizers', 'none')
cases = report['cases']
assert [(row['owner'], row['scenario']) for row in cases] == [(owner, i) for owner in owners for i in range(6)]
ids = {}
for row in cases:
    owner, i = row['owner'], row['scenario']
    assert row['global'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['ownerMultiplier'] == (.5, 2.5, 1, .5, 2.5, 1.5)[i]
    assert row['callerMultiplier'] == (2.5, .5, 2.5, 1, .5, 2.5)[i]
    assert row['contentScale'] == (1, 1, 2, 2, 2, 3)[i]
    assert row['framebufferScale'] == (1, 1, 1, 2, 1, 2)[i]
    assert row['ownerVisible'] is (owner != 'none' and i in (0, 2))
    multiplier = row['callerMultiplier'] if owner == 'none' else row['ownerMultiplier']
    expected = 13 * row['global'] * multiplier * row['contentScale'] / row['framebufferScale']
    caller = 13 * row['global'] * row['callerMultiplier'] * row['contentScale'] / row['framebufferScale']
    fonts = row['fonts']
    assert set(fonts) == {'modal', 'body', 'child', 'popup', 'caller-before', 'caller-after'} | ({'owner-window'} if row['ownerVisible'] else set())
    for key in ('modal', 'body', 'child', 'popup'):
        assert math.isclose(fonts[key], expected, abs_tol=.002), (owner, i, key, fonts[key], expected)
    assert math.isclose(fonts['caller-before'], caller, abs_tol=.002)
    assert math.isclose(fonts['caller-after'], caller, abs_tol=.002)
    if row['ownerVisible']:
        assert math.isclose(fonts['owner-window'], expected, abs_tol=.002)
    assert set(row['actions']) == set(row['ids']) == {'keep', 'cancel'}
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(owner, row['ids'])
    assert ids[owner] == row['ids']
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
assert [row['owner'] for row in report['inactive']] == list(owners)
for row in report['inactive']:
    assert row['window'] == dict(x=44, y=55, width=211, height=133)
    assert math.isclose(row['callerFont'], 13 * 1 * 2.5 * 3 / 2, abs_tol=.002)
assert read('source-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
trace = read('source-trace.json')
assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
assert all(step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22 for step in trace['steps'])
print('Named-modal owner fonts, unrelated callers, hidden owners, thirty native cases, inactive isolation and complete project/preferences replay PASS')
