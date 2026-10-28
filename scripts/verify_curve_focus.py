"""Deferred independent replay of UUID/control-focused private curve editing."""
import json
import sys
from pathlib import Path
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'curve-focus-project.json').read_text())
report = json.loads((out / 'curve-focus-native.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1
assert report['focused'] == ['y', 'max', 'segmentInterpolation', 'displayMax']
assert report['before'] == .5 and report['after'] == .75
assert all(report[k] is True for k in ['navigationOnly', 'privateBuffer', 'modalGuard', 'oneUndoRedo'])
noise, = project['noise2D']
item, = noise['processing']
assert noise['id'] == 'curve-focus' and item['kind'] == 'CURVE'
assert item['inputs']['a']['sourceKind'] == 'Ambient Value'
assert item['inputs']['a']['ambientKey'] == 'x'
assert noise['outputBinding']['itemUuid'] == item['uuid']
curve = item['parameters']['curve']
assert (curve['min'], curve['max'], curve['displayMin'], curve['displayMax'], curve['outsideDomain']) == (0, 1, -.2, 1.2, 'CLAMP')
assert [(p['x'], p['y']) for p in curve['points']] == [(0, 0), (.5, .75), (1, 1)]
assert len({p['uuid'] for p in curve['points']}) == 3
assert all(p['segmentInterpolation'] == 'LINEAR' and p['handleMode'] == 'AUTO' for p in curve['points'])
print('CURVE_FOCUS_INDEPENDENT_PASS exact fields / private navigation / linear authored knots / unchanged modes')
