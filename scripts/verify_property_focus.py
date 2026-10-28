"""Deferred independent replay of the production Properties navigation fixture."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
report = json.loads((out / 'property-focus-native.json').read_text())
project = json.loads((out / 'property-focus-project.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1
assert report['focused'] == [
    'algorithm/parameters/value', 'algorithm', 'outputBinding', 'seedOffset',
    'parameters/value/1', 'parameters/value/2', 'parameters/amplitude', 'parameters/warpMode',
    'inputs/displacementX/type', 'inputs/displacementX/sourceKind',
    'inputs/displacementX/noiseGeneratorUuid', 'inputs/displacementX/projection',
    'inputs/displacementX/slice']
noise, zero = project['noise2D']
assert noise['algorithm']['key'] == zero['algorithm']['key'] == 'CONSTANT'
assert noise['algorithm']['parameters']['value'] == 7 and noise['seedOffset'] == 11
assert zero['algorithm']['parameters']['value'] == 0
assert noise['outputBinding']['sourceKind'] == 'Source Result'
assert noise['processing'][0]['parameters']['value'] == [1, 9]
assert noise['sharedParameters'][0]['value'] == [1, 2, 13]
warp = noise['coordinateTransforms'][0]
assert warp['kind'] == 'DOMAIN_WARP' and warp['parameters']['amplitude'] == 2
assert warp['inputs']['displacementX']['slice'] == 3.5
assert warp['inputs']['displacementX']['noiseGeneratorUuid'] == zero['uuid']
assert warp['inputs']['displacementZ']['noiseGeneratorUuid'] == zero['uuid']
# Both displacement fields are identically zero, so changing sampling coordinates cannot
# alter the raw constant source; unused typed constants and shared vectors do not select output.
assert report['before'] == 2 and report['after'] == 7
assert all(report[key] is True for key in ['draftPreserved', 'navigationOnly', 'oneUndoRedo'])
print('PROPERTY_FOCUS_INDEPENDENT_PASS exact saved controls / zero displacement / raw constant 7 / private navigation')
