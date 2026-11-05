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
# Deferred background resource commands, staged private Apply and independent public API replay.
background = out / 'background-prepare'
receipt = json.loads((background / 'background-resource-edit.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['clean', 'apply', 'discard', 'cancel', 'escape', 'retry', 'raw', 'close', 'ready-cancel', 'ready-escape', 'ready-clean-cancel', 'ready-discard-cancel']
assert [case['mode'] for case in receipt['cases']] == modes
snapshots = 0
for case in receipt['cases']:
    mode = case['mode']
    applied = mode in ('clean', 'apply', 'discard', 'retry')
    private_applied = mode in ('apply', 'retry')
    assert case['applied'] is applied and case['oneUndoRedo'] is applied
    assert case['privateApplied'] is private_applied and case['responsiveFrames'] == 12
    assert case['readyRetired'] is mode.startswith('ready-')
    assert all(case[key] is True for key in ('privateUntilPublication', 'preferencesRetained', 'originalBytesRetained'))
    directory = background / mode
    baseline = json.loads((directory / 'before.json').read_text(encoding='utf-8'))
    assert [noise['id'] for noise in baseline['noise2D']] == ['a', 'b']
    assert [noise['algorithm']['parameters']['value'] for noise in baseline['noise2D']] == [.125, .375]
    assert baseline['project']['seed'] == 5 and baseline['project']['minY'] == -2 and baseline['project']['maxY'] == 2
    layer, = baseline['worldGenerator']['layers']
    operation, = layer['operations']
    voxel, = baseline['voxelTypes']
    assert voxel['id'] == 'solid' and layer['defaults']['action'] == 'SET'
    assert layer['defaults']['voxelTypeUuid'] == voxel['uuid']
    assert operation['interpretation'] == 'DENSITY' and operation['noiseGeneratorUuid'] == baseline['noise2D'][0]['uuid']
    assert operation['overrides'] == {'threshold': .5}
    assert json.loads((directory / 'preferences-before.json').read_text(encoding='utf-8')) == json.loads((directory / 'preferences.json').read_text(encoding='utf-8'))
    for name in (['before', 'after', 'undone', 'redone'] if applied else ['before', 'after']):
        saved = json.loads((directory / (name + '.json')).read_text(encoding='utf-8'))
        jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(saved)
        expected = json.loads(json.dumps(baseline))
        target = applied and name in ('after', 'redone')
        expected['noise2D'][0]['id'] = 'renamed' if target else 'a'
        expected['noise2D'][0]['algorithm']['parameters']['value'] = .625 if target and private_applied else .125
        expected['project']['seed'] = 6 if mode == 'raw' and name == 'after' else 5
        assert saved == expected
        snapshots += 1
assert snapshots == 32
api = json.loads((background / 'api-redone.json').read_text(encoding='utf-8'))
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(api)
expected = json.loads((background / 'clean/before.json').read_text(encoding='utf-8'))
expected['noise2D'][0]['id'] = 'api-renamed'
expected['noise2D'][0]['algorithm']['parameters']['value'] = .625
assert api == expected
assert json.loads((background / 'engine-replay.json').read_text(encoding='utf-8')) == dict(formatVersion=1, snapshots=32, apiStates=3, preparedTransactions=1, fields=1470, cells=10290, solidCells=1260, roots=[2, 4])
print('BROWSER_EDIT_INDEPENDENT_PASS atomic staged Apply / background cancellation and retry / exact saved models and schema / threshold World and public API replay')
