"""Deferred independent persistence oracle for the production project/draft transition probe."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
schema = json.loads((repo / 'schema/project.schema.json').read_text())
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
report = json.loads((out / 'project-draft-native.json').read_text())
flags = ['draftCancel', 'invalidApply', 'applyUndoRedo', 'discardRetained',
         'failedSaveAborted', 'staleRejected', 'standaloneSave', 'privateBlocked']
assert set(report) == {'formatVersion', 'cases', *flags}
assert report['formatVersion'] == 1 and report['cases'] == 13
assert all(report[key] is True for key in flags)
for name, seed in [('applied', -(1 << 63)), ('retry', 17), ('standalone', 0)]:
    project = json.loads((out / f'transition-{name}-project.json').read_text())
    validator.validate(project)
    assert project['project']['seed'] == 41
    noise, = project['noise2D']
    assert noise['id'] == 'owned' and noise['seedOffset'] == seed
    assert noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': .25}
    assert not project['worldGenerator']['layers'] and not project['noise3D']
    assert not project['voxelTypes'] and not project['voxelGroups'] and not project['voxelSets'] and not project['folders']
assert (out / 'blocked-directory' / 'sentinel').read_text() == 'retained'
print('PROJECT_DRAFT_INDEPENDENT_PASS three exact saved definitions / rejected raw text absent / retained failed-save destination / schema / native report')


background = out / 'background'
receipt = json.loads((background / 'transition-draft-background-native.json').read_text())
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['apply', 'cancel', 'escape', 'queued', 'retry', 'queue', 'stale', 'close', 'guard', 'exit']
assert [case['mode'] for case in receipt['cases']] == modes
count = 0
for case in receipt['cases']:
    mode = case['mode']
    applied = mode in ('apply', 'retry', 'queue', 'guard', 'exit')
    assert case['applied'] is applied
    assert case['queuedFrames'] == case['heldFrames'] == 12
    assert case['replacements'] == 0 and case['exits'] == (1 if mode == 'exit' else 0)
    assert case['preferencesRetained'] is True and case['engineRetained'] is True
    folder = background / mode
    before = json.loads((folder / 'before.json').read_text())
    validator.validate(before)
    assert before['project']['seed'] == 41
    noise, = before['noise2D']
    assert noise['id'] == 'owned' and noise['seedOffset'] == 0
    assert noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': .125}
    names = ['before', 'after']
    if mode in ('apply', 'retry', 'queue'):
        names += ['undone', 'redone']
    for name in names:
        current = json.loads((folder / f'{name}.json').read_text())
        validator.validate(current)
        expected = json.loads(json.dumps(before))
        if name == 'redone' or name == 'after' and applied:
            expected['noise2D'][0]['algorithm']['parameters']['value'] = .625
        assert current == expected, (mode, name)
        count += 1
    preferences = json.loads((folder / 'preferences.json').read_text())
    assert preferences['appearance']['theme'] == 'Dark'
    assert preferences['performance']['workers'] == 2 and preferences['performance']['cacheMiB'] == 128
    assert 'files' not in preferences
assert count == 26
print('BACKGROUND_TRANSITION_DRAFT_INDEPENDENT_PASS ten native cases / 26 exact documents / schema / one Apply and Undo/Redo')
