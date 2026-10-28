"""Deferred independent persistence oracle for project Undo/Redo ownership."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
schema = json.loads((repo / 'schema/project.schema.json').read_text())
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
states = [json.loads((out / f'history-{name}.json').read_text()) for name in ['before', 'edited', 'undone', 'redone']]
for state in states:
    validator.validate(state)
    assert not state['noise3D'] and not state['worldGenerator']['layers'] and not state['folders']
    assert not state['voxelTypes'] and not state['voxelGroups'] and not state['voxelSets']
assert states[0] == states[2] and states[1] == states[3]
before, edited = states[:2]
assert len(before['noise2D']) == 1 and len(edited['noise2D']) == 2
assert before['project'] == edited['project']
assert before['noise2D'][0]['uuid'] == edited['noise2D'][0]['uuid']
assert [n['id'] for n in edited['noise2D']] == ['owned', 'temporary']
assert before['noise2D'][0]['seedOffset'] == 0 and [n['seedOffset'] for n in edited['noise2D']] == [17, 0]
assert before['noise2D'][0]['algorithm']['parameters'] == {'value': .25}
assert [n['algorithm']['parameters'] for n in edited['noise2D']] == [{'value': .75}, {'value': .5}]
report = json.loads((out / 'project-history-native.json').read_text())
flags = ['undoCancel', 'staleDiscardRejected', 'undoDeletedSelection', 'redoCancel', 'redoRestoredModel',
         'failedHistoryRetained', 'privateBlock', 'preferencesUnchanged', 'dirtyBaseline']
assert report['formatVersion'] == 1 and set(report) == {'formatVersion', *flags}
assert all(report[key] is True for key in flags)
print('PROJECT_HISTORY_INDEPENDENT_PASS exact before/Undo and edit/Redo documents / stable identities / absent private edits / schema / native report')
