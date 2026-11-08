"""Deferred independent World Editor geometry, explicit targets and complete project equality."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'world-editor-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('ctrlSelection', 'rangeSelection', 'privateInputPreserved'):
    assert report[key] is True, key
assert report['physicalInputVerified'] is report['physicalDpiVerified'] is False
assert report['editCallbacks'] == report['referenceCallbacks'] == [report['explicitTarget']]
assert len(report['cases']) == 6
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for i, case in enumerate(report['cases']):
    assert case['scenario'] == i
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['window'], case['body'])
    actions = case['actions']
    assert set(actions) == {'edit', 'references'}
    for action in actions.values():
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .1
    assert abs(actions['edit']['y'] - actions['references']['y']) <= .1
    assert actions['edit']['x'] + actions['edit']['width'] <= actions['references']['x'] + .1
    assert 0 <= case['submitted'] < 305 and case['selected'] == 1
before = json.loads((out / 'before.json').read_text(encoding='utf-8'))
after = json.loads((out / 'after.json').read_text(encoding='utf-8'))
assert before == after
assert len(before['worldGenerator']['layers']) == 2
assert [len(layer['operations']) for layer in before['worldGenerator']['layers']] == [300, 3]
assert before['worldGenerator']['layers'][0]['operations'][1]['uuid'] == report['explicitTarget']
print('World Editor independent selection, explicit callbacks, footer geometry and project equality PASS')
