"""Independent analytical shadow-ray/lighting replay, allocation and lifecycle evidence."""
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import jsonschema


def verify(root):
    root = Path(root)
    report = json.loads((root / 'shadow-native.json').read_text())
    assert report['formatVersion'] == 1 and report['size'] == 129
    assert report['light'] == [.4, 1, .3]
    strict = root.name.endswith('gl30')
    if strict:
        assert not report['openGL31']
    assert report['mixedUnitsPerBlock'] == [2, 128]
    assert report['fixedBudgetResolution'] == 1024
    assert report['relocatedBytes'] > 0 and 0 < report['compactionSteps'] < 1000
    assert report['cameraDraws'] == 1 and report['shadowDraws'] == 2
    assert all(report[k] for k in ('disabledCleanup', 'stateRestoredAfterFailure', 'driverObjectsDeleted', 'colorTextureBindingRestored'))
    assert report['negativeOrigin'] == [-16_777_217 * 32, -9 * 32, -16_777_219 * 32]
    frames = {f['name']: f for f in report['frames']}
    expected_names = {'fixed-off', 'fixed-shadow', 'fixed-unlit', 'stream-ground',
                      'stream-partial', 'stream-shadow', 'stream-fog', 'stream-removed',
                      'stream-negative', 'stream-disabled'}
    assert set(frames) == expected_names
    # Camera is a 16-block-wide top-down orthographic crop centred at X/Z=22.
    # A receiver at Y=1 is occluded iff its ray towards (.4,1,.3) intersects
    # the independent floating box [36,40] x [38,42] x [32,36].
    def blocked(x, z):
        low, high = 0, math.inf
        for p, d, a, b in ((x, .4, 36, 40), (1, 1, 38, 42), (z, .3, 32, 36)):
            low, high = max(low, (a-p)/d), min(high, (b-p)/d)
        return high > low
    diffuse = .35 + .65 / math.sqrt(1.25)
    data = {}
    for name, frame in frames.items():
        rgba = (root / (name + '.rgba')).read_bytes()
        data[name] = rgba
        assert len(rgba) == 129*129*4 and hashlib.sha256(rgba).hexdigest() == frame['sha256']
        for label, (px, py) in {'shadow': (64,64), 'plain': (32,96)}.items():
            sample = list(rgba[(py*129+px)*4:(py*129+px)*4+4])
            assert sample == frame[label] and sample[3] == 255
            x, z = 22 + ((px+.5)/129-.5)*16, 22 - ((py+.5)/129-.5)*16
            occluded = blocked(x,z)
            assert occluded == (label == 'shadow')
            casting = name in {'fixed-shadow','stream-shadow','stream-fog','stream-negative'}
            illumination = .35 if casting and occluded else diffuse
            if name == 'fixed-unlit':
                illumination = 1
            expected = [illumination]*3
            if name == 'stream-fog':
                # Existing orthographic renderer metric is clip.w=1, with fog distance 1.5.
                fog = (1-.55*1.5)/(.45*1.5)
                expected = [illumination*(1-fog)+background*fog for background in (.055,.075,.1)]
            assert all(abs(actual-255*value) <= 3 for actual,value in zip(sample,expected)), (name,label,sample,expected)
        stats = frame['statistics']
        assert stats['bytes'] in (0, 1 << 20)
        if name in {'fixed-shadow','stream-ground','stream-partial','stream-shadow','stream-fog','stream-removed','stream-negative'}:
            assert stats['state'] == 'READY' and stats['resolution'] == 512 and stats['bytes'] == 1 << 20
        else:
            assert stats['bytes'] == 0
    assert data['stream-ground'] == data['stream-partial'] == data['stream-removed'] == data['stream-disabled']
    assert data['stream-shadow'] == data['stream-negative']
    if not strict:
        assert report['productionViewOnly'] and report['productionShadowBytesAfterDisable'] == 0
        assert report['productionEpoch'] > 0 and report['productionSubmissionStamp'] > 0
        stages = report['productionStages']
        assert [(s['requested'],s['effective'],s['arenaBytes'],s['shadowBytes']) for s in stages] == [
            (2048,1024,4<<20,4<<20),(512,512,7<<20,1<<20),(512,0,8<<20,0)]
        assert all(s['arenaBytes'] + s['shadowBytes'] == 8 << 20 for s in stages)
        repository = Path(__file__).resolve().parent.parent
        user = json.loads((root / 'shadow-user-config.json').read_text())
        schema = json.loads((repository / 'schema/user-config.schema.json').read_text())
        jsonschema.Draft202012Validator(schema).validate(user)
        assert user['visualization']['shadows'] and user['visualization']['shadowResolution'] == 512
        assert user['performance']['sceneGpuMiB'] == 8
        project = json.loads((root / 'shadow-project.json').read_text())
        assert project['noise3D'][0]['processing'][0]['parameters']['expression'] == 'y-1.25'
        subprocess.run([sys.executable,str(repository / 'scripts/verify_profile_capture.py'),str(root/'shadow-profile.json'),str(root/'shadow-profile.csv')],check=True)
        capture = json.loads((root / 'shadow-profile.json').read_text())
        metrics = [s['value'] for s in capture['samples'] if s['kind'] == 'SCENE_GPU_BYTES' and s['label'] == 'Shadow depth-map allocation']
        assert metrics and any(v > 0 for v in metrics) and all(v in (0,1<<20,4<<20) for v in metrics)
    print('SHADOW_PASS independent ray-box occlusion / ambient-directional-fog pixels / partial-complete-removal'
          ' / mixed precision / signed framebuffer parity / bounded allocation and state cleanup; ' + report['glVersion'])


if __name__ == '__main__':
    verify(sys.argv[1])
