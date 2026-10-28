"""Independent packed-plane geometry and canonical nonlinear mixed-face replay."""
import json
import math
from pathlib import Path
import struct
import sys


def records(path):
    raw = path.read_bytes()
    assert len(raw) % 48 == 0
    return list(struct.iter_unpack('<HHHbbbBHI', raw))


def face_segments(vertices, x):
    incidence = {}
    for i in range(0, len(vertices), 3):
        triangle = vertices[i:i+3]
        for a in range(3):
            p, q = triangle[a], triangle[(a+1) % 3]
            if p[0] == x and q[0] == x and p[1:3] != q[1:3]:
                edge = tuple(sorted((p[1:3], q[1:3])))
                incidence[edge] = incidence.get(edge, 0) + 1
    # Internal edges of a face-flattened rounded triangle have two incidents; the canonical
    # open contour has one. Check incidence as well as its exact quantized coordinates.
    assert all(count <= 2 for count in incidence.values())
    return {edge for edge,count in incidence.items() if count == 1}


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def canonical_face():
    """Intersect each unit triangle on x=0 with the canonical quadratic density's zero set."""
    segments = set()
    def value(p):
        y, z = p
        return (y-16)*(y-16)/64 + .23*z - 3.417
    def crossing(p, q):
        p, q = sorted((p, q))
        vp, vq = value(p), value(q)
        t = -vp / (vq-vp)
        return tuple(math.floor(f32(p[a]+t*(q[a]-p[a]))*128+.5) for a in (0,1))
    for y in range(32):
        for z in range(32):
            a, b, c, d = (y,z), (y+1,z), (y+1,z+1), (y,z+1)
            for triangle in ((a,b,c), (a,c,d)):
                points = []
                for i in range(3):
                    p, q = triangle[i], triangle[(i+1)%3]
                    if (value(p) >= 0) != (value(q) >= 0):
                        points.append(crossing(p,q))
                if len(points) == 2 and points[0] != points[1]:
                    segments.add(tuple(sorted(points)))
    return segments


def verify(root):
    root = Path(root)
    data = json.loads((root / 'surface-lod-native.json').read_text())
    assert data['formatVersion'] == 1 and data['glVersion']
    if root.name.endswith('gl30'):
        assert not data['openGL31']
    planes = data['planes']
    assert [p['level'] for p in planes] == list(range(6))
    # A horizontal plane crosses eight tetrahedral triangles per unit square at LOD0.
    # Coarse cells cone the complete unit-triangle faces to the integer center. Counting their
    # wholly opposite and crossing face triangles gives these independent plane counts.
    triangles = [8192, 8192, 5120, 3584, 2816, 6272]
    for level, row in enumerate(planes):
        vertices = records(root / f'plane-lod-{level}.bin')
        assert len(vertices) == row['vertices'] == 3*triangles[level]
        assert row['bytes'] == len(vertices)*16 and row['units'] == 128
        maximum = (1008 if level == 0 else 2016/(1 << level))*32**3
        assert row['maximumMeshBytes'] == maximum
        assert row['workingBytes'] == 3*maximum + 8*33**3 + 131072 + 2*(maximum/7*4)
        twice_area = 0
        for i in range(0, len(vertices), 3):
            a, b, c = vertices[i:i+3]
            for v in (a,b,c):
                assert 0 <= v[0] <= 4096 and 0 <= v[1] <= 4096 and v[2] == 2081
                assert v[3:6] == (0,0,-127) and v[6:] == (255,0,128)
            cross = (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0])
            assert cross < 0
            twice_area -= cross
        assert twice_area == 2*32*32*128**2
    expected = canonical_face()
    assert expected
    for side, x in ((-1,4096),(0,0)):
        for level in range(6):
            actual = face_segments(records(root / f'curved-{side}-lod-{level}.bin'), x)
            assert actual == expected, (side,level,len(actual),len(expected),actual-expected,expected-actual)
    assert data['levels'] == [0,2,5] and data['pixel'] == [255,0,0,255]
    assert 0 < data['maximumUploadBytes'] <= 4096 and data['relocatedBytes'] > 0
    assert all(data[k] for k in ['partialLevelsRetained','framebufferParity','compactionParity','indirectParity','fixedImageReleasedArena'])
    assert len(data['framebufferHash']) == 64
    if not root.name.endswith('gl30'):
        stages = data['production']
        assert [s['name'] for s in stages] == ['near','far','high-quality','low-quality',
                                               'quality-restored','near-return','negative','stream-return']
        assert [s['level'] for s in stages] == [0,3,2,4,3,0,0,0]
        assert [s['chunk'] for s in stages] == [[0,0,0]]*6 + [[-1,0,0]]*2
        assert len({s['epoch'] for s in stages[:7]}) == 1 and stages[-1]['epoch'] != stages[0]['epoch']
        for s in stages:
            assert s['cpuBytes'] + s['reserved'] <= 256*1024**2
            assert all(abs(s['pixel'][i] - [129,255,125][i]*.35) <= 2 for i in range(3))
            assert s['pixel'][3] == 255
        assert data['viewOnly'] and data['fixedReturn'] and data['fixedMeshReleasedArena']
        project = json.loads((root / 'surface-lod-project.json').read_text())
        assert project['noise3D'][0]['processing'][0]['parameters']['expression'] == 'z-16.25'
    print('SURFACE_LOD_PASS independent packed plane area/counts/normals / canonical quadratic mixed face segments'
          ' / accepted LOD metadata / full-grid pixel parity / bounded compaction / camera-quality and fixed-return ownership; '
          + data['glVersion'])


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
