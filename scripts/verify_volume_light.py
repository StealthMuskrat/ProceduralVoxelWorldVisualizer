"""Deferred independent optical-depth reference; run only when validation resumes."""
import hashlib
import json
import math
from pathlib import Path
import sys
import jsonschema


def verify(root):
    root = Path(root)
    report = json.loads((root / 'volume-light-native.json').read_text())
    assert report['formatVersion'] == 1 and report['meshShadowBytes'] == 0
    strict = root.name.endswith('gl30')
    if strict:
        assert not report['openGL31']
    cases = {row['name']: row for row in report['cases']}
    assert set(cases) == {'off','light-8','light-16','light-32','unlit','threshold','huge','negative','gradient','zero-opacity'}
    ly, lz = 1/math.sqrt(1.25), .3/math.sqrt(1.25)
    def optical_gradient(distance):
        # Integral of clamp((2+LY*t)/4, .125, .875) along the light ray.
        turning = min(distance, 1.5/ly)
        return (2*turning+.5*ly*turning**2+3.5*(distance-turning))/4
    def expected(row):
        k = row['opacity']
        mapped = .5 if row['field'] == 'gradient-y' else 1
        sigma = k*mapped
        transmittance = math.exp(-sigma*4)
        if row['threshold'] > 1:
            return [.055,.075,.1]
        if not row['enabled'] or row['unlit']:
            emitted = 1-transmittance
        else:
            # Independent high-resolution integration of an analytical light-path integral.
            total, count = 0, 65536
            ds = 4/count
            for i in range(count):
                distance = (i+.5)*ds
                light_distance = min(2/ly,distance/lz)
                optical = optical_gradient(light_distance) if row['field'] == 'gradient-y' else light_distance
                total += sigma*math.exp(-sigma*distance)*(.35+.65*math.exp(-k*optical))*ds
            emitted = total
        return [emitted+transmittance*.055,transmittance*.075,transmittance*.1]
    frames = {}
    for name,row in cases.items():
        assert row['steps'] in (8,16,32)
        data = (root/(name+'.rgba')).read_bytes();frames[name] = data
        assert len(data) == 65*65*4 and hashlib.sha256(data).hexdigest() == row['sha256']
        index = (32*65+32)*4
        pixel = list(data[index:index+4]);assert pixel == row['pixel'] and pixel[3] == 255
        reference = expected(row)
        assert all(abs(a-255*b) <= 2.5 for a,b in zip(pixel,reference)),(name,pixel,reference)
    assert frames['off'] == frames['unlit']
    assert cases['light-32']['pixel'][0] < cases['off']['pixel'][0]
    for name in ('huge','negative'):
        assert cases[name]['pixel'] == cases['light-32']['pixel']
    if not strict:
        assert report['viewOnly']
        assert [s['streamed'] for s in report['productionStages']] == [False,True]
        assert all(s['unchanged'] and s['version'] > 0 and s['activeBytes'] > 0 for s in report['productionStages'])
        repository = Path(__file__).resolve().parent.parent
        user = json.loads((root/'volume-light-user.json').read_text())
        jsonschema.Draft202012Validator(json.loads((repository/'schema/user-config.schema.json').read_text())).validate(user)
        assert user['visualization']['volumeShadows'] and user['visualization']['volumeShadowSteps'] == 16
        project = json.loads((root/'volume-light-project.json').read_text())
        assert project['noise3D'][0]['processing'][0]['parameters']['expression'] == '1'
    print('VOLUME_LIGHT_PASS independent Beer-Lambert light/view integrals / extreme encoding / gradient clamp'
          ' / unlit-threshold-zero / fixed-streamed accepted-texture ownership; '+report['glVersion'])


if __name__ == '__main__':
    verify(sys.argv[1])
