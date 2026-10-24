"""Validate the actual Draft 2020-12 schema family and checked-in fixtures."""
from pathlib import Path
import json,jsonschema
root=Path(__file__).resolve().parent.parent
for name,fixtures in [('project',['valid-empty.json','terrain.json']),('user-config',['user-config-defaults.json']),('app-config',['app-config-defaults.json'])]:
 schema=json.loads((root/'schema'/f'{name}.schema.json').read_text())
 jsonschema.Draft202012Validator.check_schema(schema)
 validator=jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker())
 for fixture in fixtures:validator.validate(json.loads((root/'fixtures'/fixture).read_text()))
 if name=='project':
  try:validator.validate(json.loads((root/'fixtures/invalid-format-version.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Unsupported format fixture was accepted')
 print(name+' schema and fixtures PASS')
