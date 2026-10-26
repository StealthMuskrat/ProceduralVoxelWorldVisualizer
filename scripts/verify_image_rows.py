"""Independent scalar/grid, display-map, histogram and cache work expectations."""
import hashlib,json,math,struct,sys
from pathlib import Path
rows=json.loads((Path(sys.argv[1])/'image-rows-reference.json').read_text())['frames']
names=['initial','recolor','vertical','return','horizontal','zoom','forced','sourceChanged']
expected_work=[256,0,1,0,256,256,256,256]
expected_values=[65536,0,256,0,256,49024,65536,65536]
assert [r['name'] for r in rows]==names
for row,work,values in zip(rows,expected_work,expected_values):
 n=row['side'];raw=hashlib.sha256();pixels=hashlib.sha256();hist=[0]*64
 for z in range(n):
  for x in range(n):
   value=(row['panX']+(x-n/2)*row['step'])*.5+(row['panZ']+(z-n/2)*row['step'])*3+row['offset']
   raw.update(struct.pack('>d',value))
   t=max(0,min(1,(value+1)/2))
   if row['mode']=='Heatmap':
    channels=[int(max(0,min(1,1.5-abs(4*t-center)))*255) for center in [3,2,1]]
   else:channels=[int(t*255)]*3
   color=channels[0]|channels[1]<<8|channels[2]<<16|0xff000000
   pixels.update(struct.pack('>I',color));hist[max(0,min(63,int((value+1)*32)))]+=1
 assert raw.hexdigest()==row['rawSha256']
 assert pixels.hexdigest()==row['pixelSha256']
 assert hist==row['histogram'] and sum(hist)==n*n
 assert row['sampledRows']==work and row['reusedRows']==n-work
 assert row['sampledValues']==values and row['reusedValues']==n*n-values
 assert 0<=row['retainedBytes']<=row['limitBytes']<=4*1024*1024
assert rows[0]['rawSha256']==rows[1]['rawSha256']==rows[3]['rawSha256']
assert rows[0]['pixelSha256']!=rows[1]['pixelSha256']
print('IMAGE_ROWS_PYTHON_PASS eight exact raw/color/histogram digests / bounded row work')
