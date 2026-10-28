"""Deferred independent structural replay of world Properties focus and explicit overrides."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'world-setting-focus-project.json').read_text())
report = json.loads((out / 'world-setting-focus-native.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1
assert report['focused'] == [
    'settings/threshold', 'settings/probabilityDriver/outputMax', 'settings/fieldGates/kind',
    'settings/voxelStatePredicate/kind', 'settings/samplingTransforms', 'enabled',
    'override/threshold', 'settings/threshold', 'interpretation', 'noiseGeneratorUuid']
assert report['layerThreshold'] == 2 and report['overrideThreshold'] == 0
assert all(report[k] is True for k in ['navigationOnly', 'privateDraft', 'explicitOverride', 'oneUndoRedo'])
noise, = project['noise2D']
voxel, = project['voxelTypes']
layer, = project['worldGenerator']['layers']
operation, = layer['operations']
settings = layer['defaults']
assert noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': 1}
assert settings['threshold'] == 2 and settings['action'] == 'SET' and settings['voxelTypeUuid'] == voxel['uuid']
assert settings['probabilityDriver']['outputMax'] == .4 and settings['probabilityDriver']['enabled'] is False
assert settings['fieldGates']['kind'] == 'ALWAYS' and settings['voxelStatePredicate']['kind'] == 'ANY'
assert settings['samplingTransforms'] == []
assert operation['overrides'] == {'threshold': 0}
assert operation['interpretation'] == 'SELECTION' and operation['noiseGeneratorUuid'] == noise['uuid']
# The constant scalar is 1. It fails inherited threshold 2, passes explicit threshold 0,
# and passes a later layer threshold -0.5 after reset. No probability driver is enabled.
assert (1 >= settings['threshold']) is False and (1 >= operation['overrides']['threshold']) is True
print('WORLD_SETTING_FOCUS_INDEPENDENT_PASS saved controls / zero presence / unchanged choices / exact selection arithmetic')
