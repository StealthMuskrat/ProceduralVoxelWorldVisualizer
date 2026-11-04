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

# Deferred worker history confirmation/progress, complete saved models and prepared public API.
from copy import deepcopy
background = out / 'background'
background_report = json.loads((background / 'background-history.json').read_text())
assert background_report['formatVersion'] == 1 and background_report['physicalInputVerified'] is False
assert [c['mode'] for c in background_report['cases']] == ['clean', 'discard', 'cancel', 'escape', 'retry', 'close']
snapshots = 0
for case in background_report['cases']:
    mode = case['mode']; moved = mode in ['clean', 'discard', 'retry']; directory = background / mode
    assert case == dict(mode=mode, moved=moved, responsiveFrames=12, discardOnlyOnPublication=True,
        preferencesRetained=True, originalBytesRetained=True, historyBytesUnchanged=True)
    before = json.loads((directory / 'before.json').read_text())
    assert before['project']['seed'] == 17 and len(before['noise2D']) == 1
    assert before['noise2D'][0]['id'] == 'field' and before['noise2D'][0]['algorithm']['parameters'] == {'value': .625}
    for name in (['before', 'after', 'redone'] if moved else ['before', 'after']):
        project = json.loads((directory / (name + '.json')).read_text()); validator.validate(project)
        expected = deepcopy(before)
        if name == 'after' and moved: expected['noise2D'][0]['algorithm']['parameters']['value'] = .125
        assert project == expected
        snapshots += 1
assert snapshots == 15
public_result = json.loads((background / 'api-redone.json').read_text()); validator.validate(public_result)
expected = json.loads((background / 'clean/before.json').read_text())
expected['noise2D'][0]['algorithm']['parameters']['value'] = .875
assert public_result == expected
replay = json.loads((background / 'engine-replay.json').read_text())
assert replay == dict(formatVersion=1, snapshots=15, preparedSteps=2, apiStates=4, fields=399, cells=5586, roots=[2, 4])
print('BACKGROUND_HISTORY_INDEPENDENT_PASS 16 project schemas / complete values / prepared history / 399 fields / 5586 EMPTY cells')
