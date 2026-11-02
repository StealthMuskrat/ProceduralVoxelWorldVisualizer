"""Deferred independent surface/point geometry and source-isolation expectations."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
def read(name):
    return json.loads((out / (name + '.json')).read_text(encoding='utf-8'))
r = read('rectangular-preview-native')
assert r['formatVersion'] == 1 and r['cellBudget'] == 105 and r['privatePreserved']
assert read('before') == read('after')
assert read('preferences-before') == read('preferences')
assert [x['mode'] for x in r['receipts']] == ['Threshold Voxels', 'Point Cloud', 'Isosurface']
epochs = [row['epoch'] for row in r['receipts']]
assert all(b > a for a, b in zip(epochs, epochs[1:]))
for row in r['receipts']:
    assert row['extent'] == {'width': 3, 'height': 5, 'depth': 7}
    assert row['origin'] == [8, -6, -11]
    v = row['vertices']
    assert len(v) % 7 == 0 and all(math.isfinite(x) for x in v)
    vertices = [v[i:i+7] for i in range(0, len(v), 7)]
    if row['points']:
        assert row['mode'] == 'Point Cloud'
        assert [p[:3] for p in vertices] == [[x+.5, y+.5, z+.5] for y in range(5) for z in range(7) for x in range(3)]
        assert all(p[6] == 255 for p in vertices)
    else:
        area = 0
        assert len(vertices) % 3 == 0
        for i in range(0, len(vertices), 3):
            a, b, c = vertices[i:i+3]
            u, w = [b[j]-a[j] for j in range(3)], [c[j]-a[j] for j in range(3)]
            cross = [u[1]*w[2]-u[2]*w[1], u[2]*w[0]-u[0]*w[2], u[0]*w[1]-u[1]*w[0]]
            assert sum(cross[j]*a[j+3] for j in range(3)) > 0
            area += math.sqrt(sum(x*x for x in cross))/2
        if row['mode'] == 'Threshold Voxels':
            assert len(vertices) == 36 and area == 142
        else:
            assert math.isclose(area, 35, abs_tol=1e-9)
            assert all(p[0] == 1.25 and p[3] == -1 for p in vertices)
    assert all(0 <= p[0] <= 3 and 0 <= p[1] <= 5 and 0 <= p[2] <= 7 for p in vertices)
print('Rectangular preview independent PASS; 3x5x7 geometry, 142 surface area, 105 points, exact plane area 35 and complete source/preferences isolation')
