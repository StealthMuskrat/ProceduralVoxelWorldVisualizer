"""Validate native curve authoring evidence and canonical mode/schema agreement."""
import json,sys
from pathlib import Path
import jsonschema
root=Path(__file__).resolve().parent.parent
out=Path(sys.argv[1])
project=json.loads((out/'curve-controls-project.json').read_text())
report=json.loads((out/'curve-controls-native.json').read_text())
registry=json.loads((root/'registries/curve-modes.json').read_text())
schema=json.loads((root/'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker()).validate(project)
assert registry['formatVersion']==1 and registry['projectFormat']==2
expected=set()
for group,label,definition,field in [('outside','Outside domain','Curve','outsideDomain'),('interpolation','Next segment','Point','segmentInterpolation'),('handles','Handle mode','Point','handleMode')]:
 keys=[c['key'] for c in registry[group]]
 assert keys==schema['$defs'][definition]['properties'][field]['enum']
 assert all(c['help'] for c in registry[group])
 expected.update(label+'/'+key for key in keys)
assert set(report['modeChoices'])==expected and len(expected)==11
assert set(report['contextActions'])=={'selectAll','clear','delete','fit'}
assert set(report['keyboardActions'])=={'CurveDelete','CurveUndo','CurveRedo'}
assert all(report[key] is True for key in ['invalidDomainRejected','privateApply','oneUndoRedo'])
c=project['noise2D'][0]['processing'][0]['parameters']['curve']
assert (c['min'],c['max'],c['displayMin'],c['displayMax'],c['outsideDomain'])==(-1,2,-2,2,'MIRROR')
assert [(p['x'],p['y']) for p in c['points']]==[(-1,0),(.5,.75),(2,1)]
assert all(p['segmentInterpolation']=='LINEAR' for p in c['points'])
print('CURVE_CONTROLS_REFERENCE_PASS schema / 11 mode choices / menu / scoped keys / exact authored curve')
