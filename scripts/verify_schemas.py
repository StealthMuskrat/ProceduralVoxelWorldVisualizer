"""Validate the actual Draft 2020-12 schema family and checked-in fixtures."""
from pathlib import Path
import json,jsonschema
root=Path(__file__).resolve().parent.parent
for name,fixtures in [('project',['valid-empty.json','terrain.json','sampled-fields.json','world-use.json','compiled-expressions.json','parameter-drivers.json','creation-defaults-project.json','hierarchy.json','bookmarks.json','noise-authoring.json','field-operations.json','trace-workflow.json','scalar-remapping.json','warp-fields.json','placement-driver-phases.json','transform-drivers.json','work-profile-project.json','semantic-sharing.json']),('user-config',['user-config-defaults.json','user-input-modern.json','user-input-legacy.json','user-theme-custom.json','user-shadow-settings.json','user-volume-light.json','user-workspace.json']),('app-config',['app-config-defaults.json','app-config-legacy.json','app-config-creation.json']),('profile-capture',['profile-capture.json','profile-capture-gpu.json','profile-capture-work.json']),('release-metadata',['release-metadata.json'])]:
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
  for fixture in ['invalid-bookmark-2d-y.json','invalid-bookmark-3d-y.json','invalid-transform-driver.json']:
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
 if name=='user-config':
  try:validator.validate(json.loads((root/'fixtures/invalid-shadow-resolution.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Invalid shadow resolution was accepted')
 if name=='user-config':
  try:validator.validate(json.loads((root/'fixtures/invalid-volume-light.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Invalid volume light steps were accepted')
 if name=='profile-capture':
  try:validator.validate(json.loads((root/'fixtures/invalid-profile-work.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Coerced work-total capture option was accepted')
 if name=='app-config':
  try:validator.validate(json.loads((root/'fixtures/invalid-app-creation.json').read_text()))
  except jsonschema.ValidationError:pass
  else:raise AssertionError('Unknown creation signature control was accepted')
 print(name+' schema and fixtures PASS')


# Deferred portable package envelope linked to the canonical project schema.
from verify_resource_package_schema import verify as verify_resource_package_schema
verify_resource_package_schema(root)

# Deferred strict release metadata shape/semantic-version fixture.
release_schema=json.loads((root/'schema/release-metadata.schema.json').read_text())
release_validator=jsonschema.Draft202012Validator(release_schema,format_checker=jsonschema.FormatChecker())
try:release_validator.validate(json.loads((root/'fixtures/invalid-release-version.json').read_text()))
except jsonschema.ValidationError:pass
else:raise AssertionError('Invalid release prerelease identifier was accepted')

# Deferred known unique workspace visibility fixture.
workspace_schema=json.loads((root/'schema/user-config.schema.json').read_text())
try:jsonschema.Draft202012Validator(workspace_schema).validate(json.loads((root/'fixtures/invalid-workspace-visibility.json').read_text()))
except jsonschema.ValidationError:pass
else:raise AssertionError('Duplicate workspace tool visibility was accepted')
