"""Independent checks of native edits, exported contracts and elementary field formulas."""
import json, math, sys
from pathlib import Path
import jsonschema
root=Path(sys.argv[1]); report=json.loads((root/'field-operations-reference.json').read_text())
p=json.loads((root/'field-operations-project.json').read_text())
assert report==dict(formatVersion=1,renderedOperations=22,extensions=12,before=57,after=37.5,undo=57,redo=37.5,gradient2D='Vector2',gradient3D='Vector3')
contract=root/'contracts'
schema=json.loads((contract/'schema/project.schema.json').read_text());jsonschema.Draft202012Validator.check_schema(schema)
validator=jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker());validator.validate(p)
registry=json.loads((contract/'registries/noise-processing-registry.json').read_text())
assert registry['registryVersion']==6 and len(registry['extendedOperations'])==17
for dimension,rows in [(2,p['noise2D']),(3,p['noise3D'])]:
 n=rows[0];items={i['kind']:i for i in n['processing']};assert items['GRADIENT']['resultType']==f'Vector{dimension}'
 expected={k for k,d in registry['extendedOperations'].items() if dimension in d['dimensions'] and k not in {'REMAP','CLAMP','TERRACE','FALLOFF','BLEND'}}
 assert set(items)-{'EXPRESSION'}==expected
 assert all('field' in items[k]['inputs'] for k in expected & set(registry['sampledFieldOperations']))
 if dimension==2:
  assert 'TORUS' not in items and 'CYLINDER' not in items
  strength=items['MAX_FILTER']['parameters']['strength'];values=[x*x+3*z*z for z in range(2,5) for x in range(1,4)]
  assert sorted(values)==[13,16,21,28,31,36,49,52,57]
  assert 31+(max(values)-31)*strength==37.5
# Disabled experiments retain structurally legal but semantically invalid controls.
invalid=json.loads(json.dumps(p)); target=next(i for i in invalid['noise2D'][0]['processing'] if i['kind']=='MAX_FILTER');target['parameters']['radius']=5
try:validator.validate(invalid)
except jsonschema.ValidationError:pass
else:raise AssertionError('Out-of-bounds active radius accepted by generated schema')
target['enabled']=False;validator.validate(invalid)
values=json.loads((root/'independent-field-values.json').read_text())
assert math.isclose(values['2/GRADIENT'],math.sqrt(340)) and math.isclose(values['3/GRADIENT'],math.sqrt(356))
assert values['2/LAPLACIAN']==8 and values['3/LAPLACIAN']==12
assert values['3/CYLINDER']==-16 and values['3/TORUS']==12
print('FIELD_OPERATIONS_INDEPENDENT_PASS generated schema/registry / signed-distance centers / 22 operation signatures / exact neighborhood vectors / native edit and Undo-Redo')
