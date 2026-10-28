"""Deferred saved-document oracle for the production browser's private selection decisions."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'resource-selection-project.json').read_text())
report = json.loads((out / 'resource-selection-native.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1
flags = ['sameOwnerRetained', 'cancelRetained', 'rawApplyRejected', 'rangeDiscard',
         'ctrlSiblings', 'managerGuarded', 'staleDiscardRejected', 'oneUndoRedo', 'preferencesUnchanged']
assert set(report) == {'formatVersion', *flags} and all(report[key] is True for key in flags)
assert [noise['id'] for noise in project['noise2D']] == ['a', 'b', 'c']
assert [noise['algorithm']['parameters'] for noise in project['noise2D']] == [{'value': .875}, {'value': .5}, {'value': .75}]
assert all(noise['seedOffset'] == 0 and noise['algorithm']['key'] == 'CONSTANT' for noise in project['noise2D'])
assert len(project['voxelTypes']) == 1 and project['voxelTypes'][0]['id'] == 'stone'
assert not project['noise3D'] and not project['worldGenerator']['layers']
assert not project['folders'] and not project['voxelGroups'] and not project['voxelSets']
print('BROWSER_SELECTION_INDEPENDENT_PASS explicit Apply only / discarded raw and model edits absent / schema / native report')
