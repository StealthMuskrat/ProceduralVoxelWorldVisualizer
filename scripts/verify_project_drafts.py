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
