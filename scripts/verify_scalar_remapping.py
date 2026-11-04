"""Check saved native authoring and recompute its transfer independently."""
from pathlib import Path
import json,sys,math
import jsonschema
root=Path(sys.argv[1]);repo=Path(__file__).resolve().parent.parent
p=json.loads((root/'remapping-project.json').read_text())
jsonschema.Draft202012Validator(json.loads((repo/'schema/project.schema.json').read_text()),format_checker=jsonschema.FormatChecker()).validate(p)
r=json.loads((root/'remapping-native-reference.json').read_text())
assert r==dict(formatVersion=1,renderedOperations=4,before=.5,after=3.25,weight=.75,mode='ADD',oneUndoRedo=True,preciseInvalidExtent=True)
n=p['noise2D'][0];blend,falloff,remap,terrace=n['processing']
assert blend['kind']=='BLEND' and blend['parameters']['mode']=='ADD' and blend['parameters']['weight']==.75
assert blend['inputs']['b']['noiseGeneratorUuid']==p['noise3D'][0]['uuid']
a=4+8*.75;t=max(abs(2),abs(3))/8;f=a*(1-t*t*(3-2*t));v=-2+f/5*4;v=math.floor(v*4)/4
assert v==3.25 and falloff['parameters']['extent']==8
engine=json.loads((root/'remapping-engine-reference.json').read_text())
assert engine==dict(formatVersion=1,vectors=548,fixtureValue=v)
# Structural failures remain independent of engine compiler checks.
schema=json.loads((repo/'schema/project.schema.json').read_text());validator=jsonschema.Draft202012Validator(schema)
for target,key,value in [(blend,'mode','AVERAGE'),(falloff,'extent',0),(terrace,'levels',1.5),(remap,'clamp',1)]:
 original=target['parameters'].copy();target['parameters'][key]=value
 assert list(validator.iter_errors(p)),f'Invalid {key} accepted by schema'
 target['parameters']=original
print('SCALAR_REMAPPING_INDEPENDENT_PASS saved private authoring / exact 3.25 chain / typed choices / structural rejections / 548 reference vectors')

# Deferred finite affine vectors use an independent Fraction oracle and Python's final float conversion.
from fractions import Fraction
import struct
mapping=json.loads((repo/'fixtures/finite-mapping-vectors.json').read_text())
assert mapping['formatVersion']==1 and len(mapping['cases'])==24
mapped=root/'finite-mapping'
mapping_validator=jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker())
projects=0
for case in mapping['cases']:
 def exact(name): return Fraction.from_float(float.fromhex(case[name+'Hex']))
 ratio=(exact('value')-exact('inputMin'))/(exact('inputMax')-exact('inputMin'))
 if case['operation']=='remap':
  value=exact('outputMin')+ratio*(exact('outputMax')-exact('outputMin'))
 elif case['operation']=='smoothstep':
  ratio=max(Fraction(0),min(Fraction(1),ratio));value=ratio*ratio*(3-2*ratio)
 else: value=ratio
 actual=float(value)
 if actual==0 and value<0: actual=-0.0
 expected=float.fromhex(case['expectedHex'])
 assert struct.pack('>d',actual)==struct.pack('>d',expected),case['id']
 for dimension in [2,3]:
  for route in (['EXPRESSION','REMAP'] if case['operation']=='remap' else ['EXPRESSION']):
   file=mapped/f"{case['id']}-{dimension}-{route}.pvproj.json"
   project=json.loads(file.read_text())
   mapping_validator.validate(project)
   noise=project['noise2D' if dimension==2 else 'noise3D'][0]
   assert noise['id']=='mapping' and noise['dimension']==dimension
   assert noise['processing'][0]['kind']==route
   projects+=1
assert projects==72
world=json.loads((mapped/'mapping-threshold-world.pvproj.json').read_text())
mapping_validator.validate(world)
assert world['project']['minY']==-1 and world['project']['maxY']==1
assert world['worldGenerator']['layers'][0]['defaults']['threshold']==.25
report=json.loads((mapped/'finite-mapping-engine.json').read_text())
assert report==dict(formatVersion=1,cases=24,projects=72,samples=432,worldCells=420,samplingPaths=['DIRECT','GRID','POINTS'],rawBits=True,projectDataUnchanged=True)
print('FINITE_MAPPING_INDEPENDENT_PASS exact Fraction oracle / 24 binary vectors / 72 project schemas / 432 fields / 420 threshold cells')
