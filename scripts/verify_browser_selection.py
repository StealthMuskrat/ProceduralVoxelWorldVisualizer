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
# Deferred background single/range/empty/manager selection and exact saved authoring states.
background = out / 'background-apply'
receipt = json.loads((background / 'background-browser-selection.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['single', 'range', 'empty', 'manager', 'cancel', 'escape', 'retry', 'raw', 'close', 'ready-cancel', 'ready-escape']
assert [case['mode'] for case in receipt['cases']] == modes
snapshots = 0
for case in receipt['cases']:
    mode = case['mode']
    applied = mode in ('single', 'range', 'empty', 'manager', 'retry')
    assert case['applied'] is applied and case['oneUndoRedo'] is applied
    assert case['responsiveFrames'] == 12
    assert case['readyFrameCancellation'] is mode.startswith('ready-')
    assert case['selectionCount'] == (3 if mode == 'range' else 0 if mode == 'empty' else 1)
    assert all(case[key] is True for key in ('privateUntilPublication', 'selectionOutsideHistory', 'preferencesRetained', 'originalBytesRetained'))
    directory = background / mode
    baseline = json.loads((directory / 'before.json').read_text(encoding='utf-8'))
    assert [noise['id'] for noise in baseline['noise2D']] == ['a', 'b', 'c']
    assert [noise['algorithm']['parameters']['value'] for noise in baseline['noise2D']] == [.125, .375, .875]
    assert len(baseline['voxelTypes']) == 1 and baseline['voxelTypes'][0]['id'] == 'solid'
    assert baseline['worldGenerator']['layers'] == []
    assert json.loads((directory / 'preferences-before.json').read_text(encoding='utf-8')) == json.loads((directory / 'preferences.json').read_text(encoding='utf-8'))
    for name in (['before', 'after', 'undone', 'redone'] if applied else ['before', 'after']):
        saved = json.loads((directory / (name + '.json')).read_text(encoding='utf-8'))
        jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(saved)
        expected = json.loads(json.dumps(baseline))
        expected['noise2D'][0]['algorithm']['parameters']['value'] = .625 if applied and name in ('after', 'redone') else .125
        expected['noise2D'][2]['algorithm']['parameters']['value'] = .75 if mode == 'raw' and name == 'after' else .875
        assert saved == expected
        snapshots += 1
assert snapshots == 32
assert json.loads((background / 'engine-replay.json').read_text(encoding='utf-8')) == dict(formatVersion=1, snapshots=32, fields=2016, cells=9408, roots=[2, 4])
print('BROWSER_SELECTION_INDEPENDENT_PASS explicit Apply / private correction / background selection / exact saved models / schema / native and engine receipts')
