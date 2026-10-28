"""Deferred independent saved-model oracle for guarded browser transactions."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'resource-edit-project.json').read_text())
report = json.loads((out / 'resource-edit-native.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
flags = ['combinedUndoRedo', 'failedCandidateRetained', 'rawDuplicateRejected', 'duplicateDiscard',
         'rawDeleteRejected', 'deleteUndoRedo', 'cancelKeptCut', 'successfulCutConsumed', 'preferencesUnchanged']
assert report['formatVersion'] == 1 and set(report) == {'formatVersion', *flags}
assert all(report[key] is True for key in flags)
noises = project['noise2D']
assert [n['id'] for n in noises] == ['renamed', 'b', 'c']
assert [n['seedOffset'] for n in noises] == [17, 0, 0]
assert [n['algorithm']['parameters'] for n in noises] == [{'value': .25}, {'value': .5}, {'value': .75}]
assert all(n['algorithm']['key'] == 'CONSTANT' for n in noises)
folder, = project['folders']
assert folder['id'] == 'folder' and folder['rootKind'] == 'noise2D' and folder['parentFolderUuid'] is None
assert noises[0]['parentFolderUuid'] == folder['uuid'] and all(n['parentFolderUuid'] is None for n in noises[1:])
assert folder['childOrder'] == [noises[0]['uuid']]
assert not project['noise3D'] and not project['voxelTypes'] and not project['voxelGroups'] and not project['voxelSets']
assert not project['worldGenerator']['layers']
print('BROWSER_EDIT_INDEPENDENT_PASS atomic Apply / absent rejected edits / exact hierarchy / schema / native report')
