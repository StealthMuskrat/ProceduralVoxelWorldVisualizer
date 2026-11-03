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
    'collapsedInputPreserved', 'rawSeedPreserved', 'exactLongSliders'])
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


# Deferred observational Project/Performance/Profiler guidance before the settings edit replay.
guidance_dir = out / 'guidance'
guidance = json.loads((guidance_dir / 'settings-guidance-native.json').read_text(encoding='utf-8'))
assert guidance['formatVersion'] == 1 and len(guidance['routes']) == 15
expected = {(name, part, topic)
            for name, topic in [('project/seed', 'settings.project.seed'),
                                ('project/minY', 'settings.project.minY'),
                                ('project/maxY', 'settings.project.maxY'),
                                ('project/seed-invalid', 'settings.project.seed'),
                                ('performance/workers', 'settings.performance.workers'),
                                ('performance/previewCellBudget', 'settings.performance.previewCellBudget'),
                                ('capture/duration', 'settings.capture.duration')]
            for part in ('slider', 'text')}
expected.add(('capture/work', 'control', 'settings.capture.work'))
assert {(route['case'], route['part'], route['topic']) for route in guidance['routes']} == expected
for route in guidance['routes']:
    if route['case'] in ('project/seed', 'performance/previewCellBudget'):
        assert route['raw'] == '9007199254740993'
    if route['case'] == 'project/seed-invalid':
        assert route['raw'] == '-'
for name in ('project-seed', 'project-minY', 'project-maxY', 'project-seed-invalid', 'performance-workers', 'performance-previewCellBudget', 'capture-duration'):
    copied = (guidance_dir / ('settings-help-' + name + '.txt')).read_text(encoding='utf-8')
    assert 'Settings fixture 100% ### snow 雪 / ' + name.replace('-', '/', 1) in copied
    assert 'help.settings-guidance' in copied
assert guidance['statePreserved'] is True
assert guidance['preferencesWritten'] is guidance['captureStarted'] is guidance['physicalInputVerified'] is False
assert not (guidance_dir / 'must-not-save.json').exists()
print('PROJECT_SETTINGS_INDEPENDENT_PASS exact metadata / private text / guidance / ambient arithmetic / inherited ALL placement')
