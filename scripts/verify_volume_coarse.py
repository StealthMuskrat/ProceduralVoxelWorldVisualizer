"""Deferred independent coarse-grid addressing, copy eligibility and physical optics checks."""
from pathlib import Path
import hashlib
import json
import math
import struct
import sys


def verify(root):
    root = Path(root)
    report = json.loads((root / 'volume-coarse-native.json').read_text())
    assert report['formatVersion'] == 1
    cases = report['cases']
    assert [r['id'] for r in cases] == ['initial', 'aligned', 'different-phase', 'coarser', 'coarse-aligned', 'refined']
    assert [r['copiedBytes'] for r in cases] == [0, 8192, 0, 0, 1024, 0]
    assert [r['sampleStep'] for r in cases] == [2, 2, 2, 4, 4, 1]
    for row in cases:
        n, step = row['textureSide'], row['sampleStep']
        assert n * step == row['extent'] == 32
        raw = (root / (row['id'] + '.bin')).read_bytes()
        assert len(raw) == row['destinationBytes'] == 4*n**3
        assert 0 < row['maximumBytes'] <= 511
        assert row['frameSha256'] == row['referenceSha256']
        ox, oy, oz = row['origin']
        for i, (actual,) in enumerate(struct.iter_unpack('<f', raw)):
            x = ox + (i % n + .5)*step
            y = oy + (i//n % n + .5)*step
            z = oz + (i//(n*n) + .5)*step
            assert actual == .5 + (x + 2*y + 4*z)/256, (row['id'], i, actual)
    optics = report['optics']
    assert len(optics) == 6
    for light in (False, True):
        group = [r for r in optics if r['light'] == light]
        assert [r['sampleStep'] for r in group] == [1, 2, 4]
        assert len({r['sha256'] for r in group}) == 1
        # Same 128 midpoint view segments as the documented integrator; extinction and
        # light-box distances are analytical and do not depend on any texture code.
        sigma, extent, ds = .01, 32, 32/128
        alpha = 1-math.exp(-sigma*ds)
        light_x, light_y, light_z = (.4/math.sqrt(1.25), 1/math.sqrt(1.25), .3/math.sqrt(1.25))
        emitted = 0
        for i in range(128):
            illumination = 1
            if light:
                depth = (i+.5)*ds
                distance = min(16/light_x, 16/light_y, depth/light_z)
                illumination = .35+.65*math.exp(-sigma*distance)
            emitted += math.exp(-sigma*i*ds)*alpha*illumination
        transmittance = math.exp(-sigma*extent)
        expected = [255*(emitted+transmittance*.055), 255*transmittance*.075, 255*transmittance*.1, 255]
        for row in group:
            assert row['textureSide']*row['sampleStep'] == row['extent'] == 32
            raw = (root/(row['id']+'.rgba')).read_bytes()
            assert len(raw) == 65*65*4
            assert hashlib.sha256(raw).hexdigest() == row['sha256']
            offset = (32*65+32)*4
            assert list(raw[offset:offset+4]) == row['pixel']
            assert all(abs(a-b) <= 2.5 for a, b in zip(row['pixel'], expected)), (row, expected)
    assert report['partialRetained'] and report['fullHostParity']
    print('VOLUME_COARSE_INDEPENDENT_PASS direct signed centers / grid spacing-phase copy gates / physical optics / self-light parity')


if __name__ == '__main__':
    verify(sys.argv[1])
