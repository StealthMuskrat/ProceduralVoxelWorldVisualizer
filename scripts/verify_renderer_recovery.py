"""Deferred independent renderer admission/retry and complete saved-project preservation."""
import json
from pathlib import Path
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('renderer-recovery-native.json')
assert report['formatVersion'] == 1 and report['physicalDpiVerified'] is False
assert report['factoryCalls'] == [1, 3, 1] and report['rendererErrors'] == 2
assert report['privateRetained'] is True and report['healthyPreviewRetained'] is True
assert isinstance(report['glVersion'], str) and report['glVersion']
expected = [('startup', [1, 1, 1], [True, False, True]),
            ('stable-failure', [1, 1, 1], [True, False, True]),
            ('healthy-tabs', [1, 1, 1], [True, False, True]),
            ('retry-failed', [1, 2, 1], [True, False, True]),
            ('recovered', [1, 3, 1], [True, True, True])]
assert len(report['phases']) == len(expected)
for phase, (name, attempts, ready) in zip(report['phases'], expected, strict=True):
    assert phase['phase'] == name
    rows = phase['renderers']
    assert [row['tab'] for row in rows] == ['2D Noise', '3D Noise', 'World']
    assert [row['attempts'] for row in rows] == attempts
    assert [row['ready'] for row in rows] == ready
    for row in rows:
        if row['ready']:
            assert row['failure'] == ''
        else:
            assert 0 < len(row['failure']) <= 1024
            assert row['failure'].startswith('Controlled 3D startup GLSL vertex compile:')
source = read('source.json')
assert source == read('saved-while-unavailable.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
assert source['formatVersion'] == 2
assert source['project']['minY'] == -4 and source['project']['maxY'] == 3
assert [noise['id'] for noise in source['noise2D']] == ['constant-2']
assert [noise['id'] for noise in source['noise3D']] == ['constant-3']
assert source['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert source['noise3D'][0]['algorithm']['parameters'] == {'value': .25}
layer, = source['worldGenerator']['layers']
placement, = layer['operations']
assert layer['defaults']['action'] == 'SET'
assert layer['defaults']['voxelTypeUuid'] == source['voxelTypes'][0]['uuid']
assert placement['noiseGeneratorUuid'] == source['noise3D'][0]['uuid']
assert placement['interpretation'] == 'DENSITY' and placement['overrides'] == {}
print('Renderer startup isolation, explicit failed/successful retry, healthy display retention and complete project/preferences preservation PASS')
