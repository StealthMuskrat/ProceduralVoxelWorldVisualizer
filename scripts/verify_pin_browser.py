"""Deferred independent replay of grouped pin editing and canonical references."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'pin-browser-project.json').read_text())
report = json.loads((out / 'pin-browser-native.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1 and report['before'] == 2 and report['after'] == 5
assert all(report[k] is True for k in ['identitySelection', 'readOnlyFilter', 'namedInputNavigation', 'canonicalValue', 'privateDraft', 'oneUndoRedo'])
noise, = project['noise2D']
parameter, = noise['sharedParameters']
add, unused = noise['processing']
assert parameter['id'] == 'gain' and parameter['type'] == 'Scalar' and parameter['value'] == 5
assert add['kind'] == 'ADD' and add['inputs']['a']['sourceKind'] == 'Shared Parameter'
assert add['inputs']['a']['parameterUuid'] == parameter['uuid']
assert add['inputs']['b']['sourceKind'] == 'Ambient Value' and add['inputs']['b']['ambientKey'] == 'x'
assert noise['outputBinding']['itemUuid'] == add['uuid'] and unused['kind'] == 'CONSTANT'
assert noise['pinnedControls'] == [
    {'targetUuid': add['uuid'], 'label': 'Sum', 'group': 'Detailed'},
    {'targetUuid': parameter['uuid'], 'label': 'Editable gain', 'group': 'Common edited'}]
assert all('value' not in pin for pin in noise['pinnedControls'])
print('PIN_BROWSER_INDEPENDENT_PASS exact authored order / canonical shared input / presentation-only pins / x+5')
