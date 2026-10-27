"""Independent signed scalar texels and CPU/GPU transfer accounting for resident overlap."""
import json
import math
from pathlib import Path
import struct
import sys


def verify(root):
    root = Path(root)
    report = json.loads((root / 'volume-overlap-native.json').read_text())
    assert report['formatVersion'] == 1
    cases = report['cases']
    assert [c['id'] for c in cases] == [
        'initial', 'positive-x', 'negative-y', 'positive-z', 'diagonal', 'same-window',
        'shrink', 'expand', 'disjoint', 'different-source', 'piecewise-initial', 'different-encoding']
    previous = None
    for c in cases:
        side = c['side']
        origin = c['origin']
        encoding = c['encoding']
        raw = (root / (c['id'] + '.bin')).read_bytes()
        assert len(raw) == 4 * side**3
        for i, (observed,) in enumerate(struct.iter_unpack('<f', raw)):
            x = origin[0] + i % side + .5
            y = origin[1] + i // side % side + .5
            z = origin[2] + i // (side*side) + .5
            value = x + 10*y + 100*z - 1000
            if c['field'] == 'negative':
                value = -value
            elif c['field'] == 'piecewise' and x >= 64:
                value = 1e300 + (x - 64)*1e298
            expected = struct.pack('<f', (value - encoding['offset']) / encoding['scale'])
            assert struct.pack('<f', observed) == expected, (c['id'], i, observed)
        copied = 0
        if previous and previous['source'] == c['source'] and all(
                struct.pack('<d', previous['encoding'][key]) == struct.pack('<d', encoding[key])
                for key in ['offset', 'scale']):
            intersection = [max(0, min(previous['origin'][a] + previous['side'], origin[a] + side)
                                - max(previous['origin'][a], origin[a])) for a in range(3)]
            copied = 4 * math.prod(intersection)
        assert c['copiedBytes'] == copied
        assert c['hostBytes'] == 4 * side**3 - copied
        assert c['hostBytes'] + c['copiedBytes'] == c['destinationBytes'] == 4*side**3
        assert 0 < c['maximumBytes'] <= 16384
        assert c['advances'] == math.ceil(c['destinationBytes']/16384)
        assert c['copiedHash'] == c['fullHash'] and len(c['fullHash']) == 64
        assert c['stateRestored']
        previous = c
    if root.name.endswith('gl30'):
        assert not report['openGL31'], report['glVersion']
    print('VOLUME_OVERLAP_PASS independent signed texels / 3D overlap and byte partition / source-encoding fallback / complete framebuffer parity / GL state; ' + report['glVersion'])


if __name__ == '__main__':
    verify(sys.argv[1])
