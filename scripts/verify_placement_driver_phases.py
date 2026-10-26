"""Independent native project, phase coordinates and exact threshold verification."""
from pathlib import Path
import json,sys,jsonschema
out=Path(sys.argv[1]);repo=Path(__file__).resolve().parent.parent
p=json.loads((out/'placement-driver-project.json').read_text());schema=json.loads((repo/'schema/project.schema.json').read_text());validator=jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker());validator.validate(p)
assert json.loads((out/'placement-driver-native.json').read_text())==dict(formatVersion=1,before=0,after=1,outputMaximum=4,oneUndoRedo=True,privateEdit=True,missingFieldRejected=True)
assert json.loads((out/'placement-driver-engine.json').read_text())==dict(formatVersion=1,checkedCells=147,outputMaximum=4.)
t=json.loads((out/'placement-driver-trace.json').read_text());assert t['finalType']==1
s=t['steps'][0];d=s['details'];rows=d['parameters']
assert [r['row'] for r in rows]==[0,2,1] and [r['phase'] for r in rows]==['SAMPLING','INTERPRETATION','ACCEPTANCE']
assert rows[0]['sampleCoordinates']==dict(x=0.,y=0.,z=0.) and rows[0]['sample']==0
assert rows[0]['effective']==2+(4-2)*.5==3 and s['field']==3
assert d['sampleCoordinates']==dict(x=3.,y=0.,z=0.) and d['probability']==1
row=p['worldGenerator']['layers'][0]['defaults']['parameterDrivers'][0];row['noiseGeneratorUuid']=None
assert list(validator.iter_errors(p)),'Coordinate driver without field accepted by schema'
registry=json.loads((repo/'registries/world-operation-registry.json').read_text());assert registry['registryVersion']==4 and len(registry['parameterTargets'])==11
assert registry['parameterDefinitions']['offsetX']==dict(phase='SAMPLING',explicitField=True,unitRange=False)
print('PLACEMENT_DRIVER_INDEPENDENT_PASS exact baseline/final coordinates / stable phase indices / native private editing / 147 cells and required source schema')
