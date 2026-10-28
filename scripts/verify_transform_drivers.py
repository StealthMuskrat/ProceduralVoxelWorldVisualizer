"""Deferred independent checks for the transform-row engine replay and authored contracts."""
from pathlib import Path
import json
import sys
import jsonschema

repo = Path(__file__).resolve().parent.parent
out = Path(sys.argv[1])
project = json.loads((out / 'transform-driver-project.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
validator.validate(project)
report = json.loads((out / 'transform-driver-engine.json').read_text())
assert report['formatVersion'] == 1
samples = report['samples']
assert len(samples) == 441
expected_keys = {(root, x, y, z) for root in (2, 4, 8)
                 for x in range(-3, 4) for y in range(-3, 4) for z in range(-1, 2)}
assert {(s['root'], s['x'], s['y'], s['z']) for s in samples} == expected_keys
for sample in samples:
    field = 1 + 2 * (sample['x'] + sample['y'])
    assert sample['field'] == field
    assert sample['type'] == int(field >= 2.5)
step = report['trace']['steps'][0]
assert step['field'] == 5 and report['trace']['finalType'] == 1
rows = step['details']['parameters']
assert [r['transformIndex'] for r in rows] == [0, 1, 1]
assert [r['destination'] for r in rows] == ['transformX', 'transformX', 'transformPivotX']
assert [r['previous'] for r in rows] == [0, 1, 0]
assert [r['effective'] for r in rows] == [1, 2, 1]
assert all(r['phase'] == 'SAMPLING' and r['sample'] == 1
           and r['sampleCoordinates'] == dict(x=2, y=1, z=0) for r in rows)
assert step['details']['sampleCoordinates'] == dict(x=5, y=0, z=0)
registry = json.loads((repo / 'registries/world-operation-registry.json').read_text())
assert registry['registryVersion'] == 5
for component in ('X', 'Y', 'Z', 'PivotX', 'PivotY', 'PivotZ'):
    definition = registry['parameterDefinitions']['transform' + component]
    assert definition['phase'] == 'SAMPLING' and definition['explicitField']
    assert definition['transformIndexRequired'] and not definition['unitRange']
row = project['worldGenerator']['layers'][0]['defaults']['parameterDrivers'][0]
for value in (None, -1, 64, .5, '0'):
    row['transformIndex'] = value
    assert list(validator.iter_errors(project)), value
row.pop('transformIndex')
assert list(validator.iter_errors(project))
print('TRANSFORM_DRIVER_INDEPENDENT_PASS 441 cells / fixed baseline / per-row slots / schema and registry')
