"""Deferred independent math/raw-bit replay for conservative semantic input sharing."""
from pathlib import Path
import csv
from collections import Counter
import json
import struct
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'semantic-sharing-project.json').read_text())
report = json.loads((out / 'semantic-sharing-engine.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1 and report['observations'] == 746 and report['paddingPreserved'] is True
assert report['sharing'] == {'diamond': 2, 'projected': 1}
diamond, projected = project['noise2D']
assert diamond['id'] == 'diamond' and projected['id'] == 'projected'
assert report['executed'] == {
    'diamond': [diamond['processing'][i]['uuid'] for i in [0, 2, 4]],
    'projected': [projected['processing'][i]['uuid'] for i in [0, 2]]}
with (out / 'semantic-sharing-values.tsv').open(newline='') as source:
    rows = list(csv.DictReader(source, delimiter='\t'))
assert len(rows) == 746
counts = {}
for row in rows:
    phase, resource = row['phase'], row['resource']
    counts[phase] = counts.get(phase, 0) + 1
    assert resource in ['diamond', 'projected']
    x, z = float(row['x']), float(row['z'])
    expected = 2 * (x + 2) * (x + 2) if resource == 'diamond' else 2*x + 8*z + 14
    bits, = struct.unpack('>Q', struct.pack('>d', expected))
    assert float(row['value']) == expected and int(row['bits'], 16) == bits
assert counts == {'direct': 578, 'grid': 40, 'concurrent': 128}
actual_points = Counter((r['phase'], r['resource'], float(r['x']), float(r['z'])) for r in rows)
expected_points = Counter()
for resource in ['diamond', 'projected']:
    expected_points.update(('direct', resource, x, z) for x in range(-8, 9) for z in range(-8, 9))
    expected_points.update(('grid', resource, -3+x*.5, 2-z*.25) for x in range(5) for z in range(4))
expected_points.update(('concurrent', 'diamond', x, 3) for x in range(-64, 64))
assert actual_points == expected_points
linear, = project['noise3D']
assert linear['dimension'] == 3 and linear['processing'][0]['parameters']['expression'] == 'x+2*y+4*z'
for operation in projected['processing'][:2]:
    ref = operation['inputs']['a']
    assert ref['sourceKind'] == 'Noise Generator Reference' and ref['noiseGeneratorUuid'] == linear['uuid']
    assert ref['projection'] == 'XZ' and ref['slice'] == 3
registry = json.loads((repo / 'registries/noise-processing-registry.json').read_text())
assert registry['registryVersion'] == 6
policy = registry['compilerSharing']
assert policy['requiresCompleteFiniteSuccess'] is True and policy['reassociatesArithmetic'] is False
assert len(policy['inputIdentities']) == 5
print('SEMANTIC_SHARING_INDEPENDENT_PASS 746 exact raw-bit values / trace identities / diamond and projection math / registry scope')
