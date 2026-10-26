"""Validate the actual Draft 2020-12 schema family and checked-in fixtures."""
from pathlib import Path
import json,jsonschema
root=Path(__file__).resolve().parent.parent
for name,fixtures in [('project',['valid-empty.json','terrain.json','sampled-fields.json','world-use.json','compiled-expressions.json','parameter-drivers.json','creation-defaults-project.json','hierarchy.json','bookmarks.json','noise-authoring.json','field-operations.json','trace-workflow.json','scalar-remapping.json']),('user-config',['user-config-defaults.json','user-input-modern.json','user-input-legacy.json','user-theme-custom.json']),('app-config',['app-config-defaults.json','app-config-legacy.json','app-config-creation.json']),('profile-capture',['profile-capture.json'])]:
 schema=json.loads((root/'schema'/f'{name}.schema.json').read_text())
 jsonschema.Draft202012Validator.check_schema(schema)
 validator=jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker())
 for fixture in fixtures:validator.validate(json.loads((root/'fixtures'/fixture).read_text()))
 if name=='project':
  curve_schema={'$ref':'#/$defs/Curve','$defs':schema['$defs']}
  jsonschema.Draft202012Validator(curve_schema,format_checker=jsonschema.FormatChecker()).validate(json.loads((root/'fixtures/curve.json').read_text()))
  try:validator.validate(json.loads((root/'fixtures/invalid-format-version.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Unsupported format fixture was accepted')
 if name=='project':
  try:validator.validate(json.loads((root/'fixtures/invalid-hierarchy-order.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Duplicate root order was accepted')
 if name=='project':
  for fixture in ['invalid-bookmark-2d-y.json','invalid-bookmark-3d-y.json']:
   try:validator.validate(json.loads((root/'fixtures'/fixture).read_text()))
   except jsonschema.ValidationError:pass
   else:raise AssertionError('Invalid bookmark coordinate shape was accepted: '+fixture)
 if name=='user-config':
  try:validator.validate(json.loads((root/'fixtures/invalid-shortcut-action.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Unknown shortcut action was accepted')
 if name=='user-config':
  try:validator.validate(json.loads((root/'fixtures/invalid-theme-color.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Invalid theme color was accepted')
 if name=='app-config':
  try:validator.validate(json.loads((root/'fixtures/invalid-app-creation.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Unknown creation signature control was accepted')
 print(name+' schema and fixtures PASS')
