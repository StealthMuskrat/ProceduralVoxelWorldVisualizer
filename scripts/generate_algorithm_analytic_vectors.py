"""Independent Python double/hash oracle; never imports or executes the Java implementation."""
from pathlib import Path
import json,math,struct,uuid
MASK=(1<<64)-1
def mix(x):
 x&=MASK;x=((x^(x>>30))*0xbf58476d1ce4e5b9)&MASK;x=((x^(x>>27))*0x94d049bb133111eb)&MASK;return x^(x>>31)
def rot(x,n):return ((x<<n)|(x>>(64-n)))&MASK
def bits(x):return struct.unpack('>Q',struct.pack('>d',float(x)))[0]
def seed(runtime,identity,offset):
 u=uuid.UUID(identity).int;return mix(runtime^mix(u>>64)^rot(mix(u&MASK),17)^mix(offset))
points=[[-0.0,0.0,-0.0],[0,0,0],[3,4,0],[-3,-4,12],[-31.5,7.125,32.5],[1024.25,-512.5,256.75]]
rows=[]
for key in ['WHITE_HASH','CONSTANT','GRADIENT_Y','SPHERE_SDF','BOX_SDF']:
 for dimension in [2,3]:
  for raw in [0,1,MASK,0x123456789abcdef0]:
   for x,y,z in points:
    params={'frequency':.125} if key=='WHITE_HASH' else {'value':-0.0} if key=='CONSTANT' else {'radius':5.} if key=='SPHERE_SDF' else {'extentX':2.,'extentY':3.,'extentZ':4.} if key=='BOX_SDF' else {}
    if key=='WHITE_HASH':value=(mix(raw^mix(bits(x*.125))^rot(mix(bits((0.0 if dimension==2 else y)*.125)),21)^rot(mix(bits(z*.125)),42))>>11)*2.0**-52-1
    elif key=='CONSTANT':value=-0.0
    elif key=='GRADIENT_Y':value=z if dimension==2 else y
    elif key=='SPHERE_SDF':value=math.sqrt(x*x+(0 if dimension==2 else y*y)+z*z)-5
    else:
     qx=abs(x)-2;qy=float('-inf') if dimension==2 else abs(y)-3;qz=abs(z)-4
     ax=max(qx,0);ay=max(qy,0);az=max(qz,0);value=math.sqrt(ax*ax+ay*ay+az*az)+min(max(qx,qy,qz),0)
    rows.append(dict(algorithm=key,dimension=dimension,seedHex=f'{raw:016x}',x=x,y=y,z=z,parameters=params,valueBits=f'{bits(value):016x}'))
seeds=[]
for runtime in [0,1,MASK,1<<63]:
 for identity in ['00000000-0000-4000-8000-000000000001','ffffffff-ffff-4fff-bfff-ffffffffffff']:
  for offset in [0,1,MASK,1<<63]:seeds.append(dict(runtimeHex=f'{runtime:016x}',uuid=identity,offsetHex=f'{offset:016x}',valueHex=f'{seed(runtime,identity,offset):016x}'))
path=Path(__file__).resolve().parent.parent/'fixtures/algorithm-analytic-vectors.json'
path.write_text(json.dumps(dict(formatVersion=1,oracle='Independent Python IEEE-double and unsigned SplitMix64 formulas',vectors=rows,seedVectors=seeds),indent=2)+'\n')
print(f'Generated {len(rows)} analytic/hash and {len(seeds)} seed vectors')
