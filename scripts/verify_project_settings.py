"""Deferred independent saved settings, private-buffer report and ambient-field replay."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'project-settings-project.json').read_text())
report = json.loads((out / 'project-settings-native.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1 and report['focused'] == ['seed', 'minY', 'maxY']
assert all(report[k] is True for k in [
    'invalidTextRetained', 'staleApplyRejected', 'explicitReload', 'cancel',
    'resourceDraftPreserved', 'oneUndoRedo', 'sameListRetryDetails', 'throwDetailsCleared',
    'collapsedInputPreserved', 'rawSeedPreserved'])
assert report['candidateDiagnostics'] == 300 and report['visibleDiagnostics'] == 8
assert report['rejectedAttempts'] == 3
settings = project['project']
assert (settings['seed'], settings['minY'], settings['maxY']) == (17, -2, 3)
noise, = project['noise2D']
item, = noise['processing']
assert noise['id'] == 'context' and item['kind'] == 'EXPRESSION'
assert item['parameters']['expression'] == 'x+projectSeed+worldMinY+worldMaxY+worldHeight'
assert noise['outputBinding']['itemUuid'] == item['uuid']
height = settings['maxY'] - settings['minY'] + 1
expected = settings['seed'] + settings['minY'] + settings['maxY'] + height
assert expected == report['after'] == 24
assert 0 - 64 + 319 + (319 - (-64) + 1) == report['before'] == 639
layer, = project['worldGenerator']['layers']
operation, = layer['operations']
voxel, = project['voxelTypes']
assert layer['defaults']['action'] == 'SET' and layer['defaults']['voxelTypeUuid'] == voxel['uuid']
assert operation['interpretation'] == 'ALL' and operation['noiseGeneratorUuid'] == noise['uuid']
assert operation['overrides'] == {}
print('PROJECT_SETTINGS_INDEPENDENT_PASS exact metadata / private text report / ambient arithmetic / inherited ALL placement')
