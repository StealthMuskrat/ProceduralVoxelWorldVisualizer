"""Independent arithmetic warp oracle; never imports or invokes Java."""
from pathlib import Path
import json,sys,itertools
root=Path(__file__).resolve().parent.parent
output=Path(sys.argv[1]) if len(sys.argv)>1 else root/'fixtures/warp-vectors.json'
vectors=[]
for dimension in [2,3]:
 for mode in ['INDEPENDENT','PROGRESSIVE','SEQUENTIAL']:
  orders=['XZ','ZX'] if dimension==2 else [''.join(p) for p in itertools.permutations('XYZ')]
  for order in orders:
   for octaves in [1,2,3]:
    for frequency in [.5,1]:
     for amplitude in [-1,0,.25]:
      original=[2.,-1. if dimension==3 else 0.,3.];position=original.copy()
      def displacement(axis,p,scale):
       x,y,z=[q*scale*frequency for q in p]
       return [z+.5*y,x-z,x-.25*y][axis]
      for octave in range(octaves):
       amount=amplitude*(.5**octave);scale=2.**octave
       if mode=='SEQUENTIAL':
        for name in order:
         axis='XYZ'.index(name);position[axis]+=amount*displacement(axis,position,scale)
       else:
        query=original if mode=='INDEPENDENT' else position.copy()
        for axis in ([0,2] if dimension==2 else [0,1,2]):position[axis]+=amount*displacement(axis,query,scale)
      vectors.append(dict(dimension=dimension,mode=mode,order=order,octaves=octaves,frequency=frequency,amplitude=amplitude,input=original,position=position,result=position[0]+10*position[1]+100*position[2]))
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(dict(formatVersion=1,vectors=vectors),indent=2)+'\n')
