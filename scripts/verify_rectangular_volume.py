"""Deferred independent box clipping, absorption, texture-order and source-isolation expectations."""
import json
import math
from pathlib import Path
import struct
import sys

out=Path(sys.argv[1])
def read(name):
    return json.loads((out/(name+'.json')).read_text(encoding='utf-8'))
def f32(v):
    return struct.unpack('f',struct.pack('f',v))[0]
r=read('rectangular-volume-native')
assert r['formatVersion']==1 and r['bytes']==420
assert r['privatePreserved'] is True
assert read('before')==read('after')
assert read('preferences-before')==read('preferences')
assert r['editorShape']==[3,5,7] and r['editorOrigin']==[8.75,-5.5,-11]
expected=[(8.75+x+.5)+10*(-5.5+y+.5)+100*(-11+z+.5)
          for z in range(7) for y in range(5) for x in range(3)]
assert r['editorTexels']==expected
assert r['overlapCopiedBytes']==2*3*6*4 and r['overlapWrittenBytes']==4*3*6*4
expected_overlap=[(1+x+.5)+10*(1+y+.5)+100*(1+z+.5)
                  for z in range(6) for y in range(3) for x in range(4)]
assert r['overlapTexels']==expected_overlap
assert len(r['rows'])==3
for row in r['rows']:
    x,y,z,yaw,pitch=row['camera']
    direction=[-math.sin(yaw)*math.cos(pitch),-math.sin(pitch),-math.cos(yaw)*math.cos(pitch)]
    origin=[p+f32(.05)*d for p,d in zip((x,y,z),direction)]
    assert all(math.isclose(a,b,abs_tol=1e-12,rel_tol=0) for a,b in zip(direction,row['direction']))
    assert all(math.isclose(a,b,abs_tol=1e-12,rel_tol=0) for a,b in zip(origin,row['origin']))
    enter,leave=0,float('inf')
    for p,d,extent in zip(origin,direction,(3,5,7)):
        if abs(d)<1e-12:
            assert 0<=p<=extent
        else:
            a,b=-p/d,(extent-p)/d
            enter,leave=max(enter,min(a,b)),min(leave,max(a,b))
    length=max(0,leave-enter)
    assert math.isclose(length,row['path'],rel_tol=0,abs_tol=1e-10)
    transmittance=math.exp(-f32(.2)*length)
    expected_pixel=[255*(1-transmittance+transmittance*.055),255*transmittance*.075,255*transmittance*.1]
    assert all(abs(a-b)<=2 for a,b in zip(row['rgba'][:3],expected_pixel))
    assert row['rgba'][3]==255
assert math.isclose(r['rows'][0]['path'],7,abs_tol=1e-10)
assert math.isclose(r['rows'][1]['path'],3,abs_tol=1e-10)
assert math.isclose(r['rows'][2]['path'],5/math.sin(math.pi/3),abs_tol=1e-10)
print('Rectangular volume independent PASS; 105 editor texels, 72 overlap texels, bounded bytes and three analytic absorption paths')
