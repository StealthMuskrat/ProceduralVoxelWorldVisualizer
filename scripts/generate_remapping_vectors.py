"""Regenerate independent mathematical manipulation vectors without invoking Java."""
from pathlib import Path
import json, math, sys
root=Path(__file__).resolve().parent.parent
output=Path(sys.argv[1]) if len(sys.argv)>1 else root/'fixtures/remapping-vectors.json'
output.parent.mkdir(parents=True,exist_ok=True)
# Independent analytical corpus. No Java/compiler import; expectations computed in Python.
vectors=[]
def case(kind,dimension,params,a,x=0,y=0,z=0,b=0,w=None,expected=None):vectors.append(dict(kind=kind,dimension=dimension,parameters=params,a=a,b=b,weight=w,x=x,y=y,z=z,expected=expected))
for dim in [2,3]:
 for levels in [1,4,8]:
  for transition in [0,.5,1]:
   for a in [-2.125,-1,-.125,0,.0625,.125,.9375,1,2.125]:
    cell=math.floor(a*levels);fraction=a*levels-cell;t=0 if transition==0 else min(1,max(0,(fraction-1+transition)/transition));v=(cell+t*t*(3-2*t))/levels
    case('TERRACE',dim,dict(levels=levels,transition=transition),a,expected=v)
 for shape in ['LINEAR','SMOOTHSTEP','SMOOTHERSTEP','COSINE']:
  for outside in ['CLAMP','EXTEND']:
   for metric in ['EUCLIDEAN','CHEBYSHEV','MANHATTAN','AXIS']:
    for x,y,z in [(0,0,0),(3,0,4),(-8,2,0),(16,0,0)]:
     dist={'EUCLIDEAN':math.sqrt(x*x+(y*y if dim==3 else 0)+z*z),'CHEBYSHEV':max(abs(x),abs(y) if dim==3 else 0,abs(z)),'MANHATTAN':abs(x)+(abs(y) if dim==3 else 0)+abs(z),'AXIS':abs(x)}[metric]
     t=dist/8
     if outside=='CLAMP':t=min(1,max(0,t))
     q={'LINEAR':lambda:t,'SMOOTHSTEP':lambda:t*t*(3-2*t),'SMOOTHERSTEP':lambda:t*t*t*(t*(6*t-15)+10),'COSINE':lambda:(1-math.cos(math.pi*t))*.5}[shape]()
     case('FALLOFF',dim,dict(extent=8,shape=shape,outside=outside,metric=metric),-4,x,y,z,expected=-4*(1-q))
 for mode in ['LERP','ADD','SUBTRACT','MULTIPLY','DIVIDE','MIN','MAX']:
  for w in [-.5,0,.25,1,1.5]:
   a,b=-2,4;target={'LERP':b,'ADD':a+b,'SUBTRACT':a-b,'MULTIPLY':a*b,'DIVIDE':a/b,'MIN':min(a,b),'MAX':max(a,b)}[mode]
   case('BLEND',dim,dict(mode=mode,weight=w),a,b=b,expected=a if w==0 else target if w==1 else a+(target-a)*w)
 for ends in [(-1,1,-2,4),(1,-1,4,-2),(-1,1,4,-2)]:
  lo,hi,ol,oh=ends
  for clamp in [False,True]:
   for a in [-3,-1,0,1,3]:
    v=ol+(a-lo)/(hi-lo)*(oh-ol)
    if clamp:v=min(max(ol,oh),max(min(ol,oh),v))
    case('REMAP',dim,dict(inputMin=lo,inputMax=hi,outputMin=ol,outputMax=oh,clamp=clamp),a,expected=v)
output.write_text(json.dumps(dict(formatVersion=1,vectors=vectors),indent=2)+'\n')
