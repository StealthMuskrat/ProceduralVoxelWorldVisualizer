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
# Deferred background Apply: independent complete saved values, inclusive bounds and replay receipts.
background = out / 'background-apply'
apply_report = json.loads((background / 'project-settings-apply.json').read_text(encoding='utf-8'))
assert apply_report['formatVersion'] == 1 and apply_report['physicalInputVerified'] is False
modes = ['success', 'cancel', 'escape', 'raw', 'retry', 'close']
assert [case['mode'] for case in apply_report['cases']] == modes
snapshots = 0
for case in apply_report['cases']:
    mode = case['mode']
    applied = mode in ('success', 'retry')
    assert case['applied'] is applied and case['oneUndoRedo'] is applied
    assert case['responsiveFrames'] == 12
    assert all(case[key] is True for key in ('privatePropertiesRetained', 'rawTextOwned',
                                           'preferencesRetained', 'originalBytesRetained'))
    directory = background / mode
    baseline = json.loads((directory / 'before.json').read_text(encoding='utf-8'))
    assert (baseline['project']['seed'], baseline['project']['minY'], baseline['project']['maxY']) == (5, -2, 2)
    preferences = json.loads((directory / 'preferences.json').read_text(encoding='utf-8'))
    assert preferences == json.loads((background / 'success/preferences.json').read_text(encoding='utf-8'))
    for name in (['before', 'after', 'undone', 'redone'] if applied else ['before', 'after']):
        saved = json.loads((directory / (name + '.json')).read_text(encoding='utf-8'))
        jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(saved)
        expected = json.loads(json.dumps(baseline))
        target = applied and name in ('after', 'redone')
        seed = 17 if target else 6 if mode == 'raw' and name == 'after' else 5
        min_y, max_y = (-1, 1) if target else (-2, 2)
        expected['project'].update(seed=seed, minY=min_y, maxY=max_y)
        assert saved == expected
        noise, = saved['noise2D']
        item, = noise['processing']
        voxel, = saved['voxelTypes']
        layer, = saved['worldGenerator']['layers']
        operation, = layer['operations']
        assert noise['id'] == 'ambient' and item['kind'] == 'EXPRESSION'
        assert item['parameters']['expression'] == 'x+projectSeed+worldMinY+worldMaxY+worldHeight'
        assert noise['outputBinding']['itemUuid'] == item['uuid']
        assert voxel['id'] == 'solid'
        assert layer['defaults']['action'] == 'SET' and layer['defaults']['voxelTypeUuid'] == voxel['uuid']
        assert operation['interpretation'] == 'ALL' and operation['noiseGeneratorUuid'] == noise['uuid']
        assert operation['overrides'] == {}
        offset = seed + min_y + max_y + max_y - min_y + 1
        assert offset == (20 if target else 11 if mode == 'raw' and name == 'after' else 10)
        snapshots += 1
assert snapshots == 16
replay = json.loads((background / 'engine-replay.json').read_text(encoding='utf-8'))
assert replay == dict(formatVersion=1, snapshots=16, fields=336, cells=4704, roots=[2, 4])
print('PROJECT_SETTINGS_INDEPENDENT_PASS exact metadata / private text / guidance / ambient arithmetic / inherited ALL placement / background Apply states')
