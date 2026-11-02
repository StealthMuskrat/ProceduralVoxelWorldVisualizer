"""Deferred independent rectangular plane sampling, accepted-image and navigation arithmetic."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
def read(name):
    return json.loads((out / (name + '.json')).read_text(encoding='utf-8'))
r = read('rectangular-slice-native')
assert r['formatVersion'] == 1
assert read('before') == read('after')
assert read('preferences-before') == read('preferences')
assert [row['plane'] for row in r['rows']] == ['X', 'Y', 'Z']
assert [(row['width'], row['height']) for row in r['rows']] == [(5, 7), (3, 7), (3, 5)]
assert [row['origin'] for row in r['rows']] == [[10.625, 3.125], [-8.375, 3.125], [-8.375, 10.625]]

def point(row, px, py):
    u = row['origin'][0] + px * row['width'] * row['step']
    v = row['origin'][1] + py * row['height'] * row['step']
    return {'X': [row['slice'], u, v], 'Y': [u, row['slice'], v], 'Z': [u, v, row['slice']]}[row['plane']]

def affine(p):
    return p[0] + 10*p[1] + 100*p[2]

for row in r['rows'] + [r['pending'], r['refreshed']]:
    assert row['slice'] == -3 and row['step'] == .75
    assert len(row['values']) == row['width'] * row['height']
    expected = [affine(point(row, x/row['width'], y/row['height']))
                for y in range(row['height']) for x in range(row['width'])]
    assert row['values'] == expected
    hover = point(row, *row['pointer'])
    actual = [row['point'][axis] for axis in ('x','y','z')]
    assert all(math.isclose(a,b,abs_tol=1e-8,rel_tol=0) for a,b in zip(hover,actual))
    assert math.isclose(row['raw'], affine(hover), abs_tol=1e-8, rel_tol=0)
assert r['pending']['plane'] == 'Z' and r['pending']['width'] == 3 and r['pending']['height'] == 5
assert r['pending']['values'] == r['rows'][-1]['values']
assert r['refreshed']['plane'] == 'X' and r['refreshed']['width'] == 4 and r['refreshed']['height'] == 2
assert r['refreshed']['origin'] == [11, 5]
before, after = r['navigation'], r['zoomed']
assert (before['width'], before['height']) == (3,5)
assert (after['width'], after['height']) == (3,5)
assert math.isclose(after['step'], before['step'] * math.exp(-.3), abs_tol=1e-15)
for coordinate, count, anchor in [('u','width',r['anchor'][0]), ('v','height',r['anchor'][1])]:
    a = before[coordinate] + (anchor*before[count]-before[count]/2)*before['step']
    b = after[coordinate] + (anchor*after[count]-after[count]/2)*after['step']
    assert math.isclose(a,b,rel_tol=0,abs_tol=1e-8)
a,b = r['panBefore'], r['panned']
for coordinate, count, delta in [('u','width',r['panDelta'][0]), ('v','height',r['panDelta'][1])]:
    assert math.isclose(b[coordinate], a[coordinate]-delta*a[count]*a['step'], rel_tol=0, abs_tol=1e-8)
print('Rectangular slice independent PASS; GPU dimensions, 79 distinct plane samples, affine hover, anchored zoom, per-axis pan and pending image geometry')
