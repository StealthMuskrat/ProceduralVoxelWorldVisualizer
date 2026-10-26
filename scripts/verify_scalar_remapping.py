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
