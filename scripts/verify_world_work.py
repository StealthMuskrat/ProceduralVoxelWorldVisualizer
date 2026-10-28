"""Deferred independent attribution identities, exact layer sums and engine-only replay checks."""
from collections import defaultdict
from pathlib import Path
import json
import subprocess
import sys

out = Path(sys.argv[1])
subprocess.run([sys.executable, str(Path(__file__).with_name('verify_profile_capture.py')),
                str(out/'world-work-capture.json'), str(out/'world-work-capture.csv')], check=True)
capture = json.loads((out/'world-work-capture.json').read_text())
project = json.loads((out/'world-work-project.json').read_text())
report = json.loads((out/'world-work-engine.json').read_text())
assert report == dict(formatVersion=1, checkedCells=288, workSamples=30, stagingReleased=True)
assert capture['state'] == 'STOPPED' and capture['options']['worldContributions'] is True
assert capture['options']['operationStride'] == 65536
assert not any(s['kind'] == 'OPERATION' for s in capture['samples'])
parents, labels = {}, {}
for layer in project['worldGenerator']['layers']:
    labels[layer['uuid']] = 'Layer '+layer['id']
    for op in layer['operations']:
        parents[op['uuid']] = layer['uuid']
        labels[op['uuid']] = layer['id']+' / '+op['id']
work = [s for s in capture['samples'] if s['kind'] in ('OPERATION_WORK', 'LAYER_WORK')]
assert len(work) == 30
sums = defaultdict(lambda: defaultdict(int))
counts = defaultdict(int)
for sample in work:
    assert sample['projectUuid'] == project['project']['uuid']
    assert sample['outcome'] == 'SUCCESS' and sample['unit'] == 'NANOSECONDS'
    assert sample['label'] == labels[sample['sourceUuid']]
    key = (sample['contextId'], tuple(sample['chunk'][axis] for axis in ('x','y','z')))
    sums[key][sample['sourceUuid']] += sample['value']
    counts[(sample['kind'], sample['sourceUuid'])] += 1
for key, sources in sums.items():
    for layer in project['worldGenerator']['layers']:
        assert sources[layer['uuid']] == sum(sources[op['uuid']] for op in layer['operations']), (key, layer)
assert set(counts.values()) == {6}
assert all(s['value'] >= 0 for s in work)
print('WORLD_WORK_INDEPENDENT_PASS immutable UUID provenance / exact peer-operation sums / 288 canonical cells / bounded exports')
