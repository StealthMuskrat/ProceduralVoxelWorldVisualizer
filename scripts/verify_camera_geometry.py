"""Deferred independent trigonometric rays, clipping and raster/voxel parity expectations."""
import json
import math
from pathlib import Path
import struct
import sys

out = Path(sys.argv[1])
r = json.loads((out / 'camera-geometry-native.json').read_text(encoding='utf-8'))
assert r['formatVersion'] == 1 and r['width'] == 97 and r['height'] == 65
assert r['physicalDpiVerified'] is False
assert len(r['rows']) == 144

def f32(value):
    return struct.unpack('f', struct.pack('f', value))[0]

def close(a, b, tolerance):
    assert len(a) == len(b) and all(math.isfinite(x) for x in a + b)
    assert all(abs(x-y) <= tolerance for x, y in zip(a, b)), (a, b)

fov = f32(math.radians(50))
tangent, near = math.tan(fov / 2), f32(.05)
keys = set()
hits = 0
for row in r['rows']:
    offset = row['offset']
    assert offset in (0, 2_000_000_000, -2_000_000_000)
    yaw, pitch = row['yaw'], row['pitch']
    assert (yaw, pitch) in ((0, 0), (math.pi / 2, .6), (-1.1, -.7))
    assert row['distance'] == 4 and isinstance(row['pan'], bool) and isinstance(row['flying'], bool)
    target = [offset + 2, 65, -offset + 3]
    assert row['target'] == target
    sy, cy, sp, cp = math.sin(yaw), math.cos(yaw), math.sin(pitch), math.cos(pitch)
    forward = [-sy * cp, -sp, -cy * cp]
    right, up = [cy, 0, -sy], [-sy * sp, cp, -cy * sp]
    focus = [value + .5 for value in target]
    if row['pan']:
        focus = [v + (-7 * right[i] - 4 * up[i]) * 4 * .0015 for i, v in enumerate(focus)]
    tolerance = 3e-6 if offset else 2e-13
    close(row['focus'], focus, tolerance)
    eye = [focus[i] - 4 * forward[i] for i in range(3)]
    close(row['eye'], eye, tolerance)
    px, py = row['pixel']
    assert (px, py) in ((48, 32), (50, 33), (46, 31), (0, 0))
    nx, ny = f32(2 * (px + .5) / 97 - 1), f32(2 * (py + .5) / 65 - 1)
    close([f32(v) for v in row['ndc']], [nx, ny], 0)
    aspect = f32(97 / 65)
    assert f32(row['aspect']) == aspect
    displacement = [forward[i] + nx * aspect * tangent * right[i] + ny * tangent * up[i] for i in range(3)]
    length = math.sqrt(sum(v*v for v in displacement))
    direction = [v / length for v in displacement]
    origin = [eye[i] + near * displacement[i] for i in range(3)]
    close(row['direction'], direction, 2e-15)
    close(row['origin'], origin, tolerance)
    # Independent slab intersection with the exact single rendered unit voxel.
    enter, leave = 0, 20
    for axis in range(3):
        if abs(direction[axis]) < 1e-15:
            if not target[axis] <= origin[axis] <= target[axis] + 1:
                leave = -1
        else:
            limits = [(target[axis] + edge - origin[axis]) / direction[axis] for edge in (0, 1)]
            enter, leave = max(enter, min(limits)), min(leave, max(limits))
    expected_hit = leave >= enter
    assert expected_hit == (px != 0)
    assert row['hit'] == (target if expected_hit else [])
    red, green, blue, alpha = row['rgba']
    assert alpha == 255
    assert (red > 100 and green < 60 and blue < 60) == expected_hit
    hits += expected_hit
    key = (offset, yaw, pitch, row['pan'], row['flying'], px, py)
    assert key not in keys
    keys.add(key)
assert hits == 108 and len(keys) == 144
assert len({key[:5] for key in keys}) == 36
print('Camera geometry independent PASS; 144 rays, 108 raster hits, 36 misses, 36 orbit/fly/pan/origin scenarios')
