"""Deferred saved-project/report replay for inline and expanded token selection."""
from pathlib import Path
import json
import sys
import jsonschema

inline, expanded = map(Path, sys.argv[1:3])
repo = Path(__file__).resolve().parent.parent
schema = json.loads((repo / 'schema/project.schema.json').read_text())
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
a = json.loads((inline / 'expression-recovery-native.json').read_text())
b = json.loads((expanded / 'expanded-expression-native.json').read_text())
assert a['unicodeTokenSelection'] is True and a['oneShotSelection'] is True
assert b['tokenSelection'] == {'unicode': True, 'oneShot': True}
assert a['before'] == b['before'] == 2 and a['after'] == 4 and b['after'] == 6
assert all(a[k] is True for k in ['keyboardFocusRepair', 'rejectedDraftPreserved', 'oneUndoRedo'])
assert all(b[k] is True for k in ['privateBuffer', 'privateStage', 'cancel', 'escape', 'oneUndoRedo'])
for directory, filename, expression, accent in [
        (inline, 'expression-recovery-project.json', 'x+é+bias+0', 2),
        (expanded, 'expanded-expression-project.json', 'x+\nbias*3', 0)]:
    project = json.loads((directory / filename).read_text())
    validator.validate(project)
    noise, = project['noise2D']
    item, = noise['processing']
    assert item['kind'] == 'EXPRESSION' and item['resultType'] == 'Scalar'
    assert item['parameters']['expression'] == expression
    assert item['inputs']['bias']['sourceKind'] == item['inputs']['é']['sourceKind'] == 'Constant'
    assert item['inputs']['bias']['value'] == 2 and item['inputs']['é']['value'] == accent
    assert noise['outputBinding']['itemUuid'] == item['uuid']
print('EXPRESSION_SELECTION_INDEPENDENT_PASS Unicode / multiline / one-shot / private buffer / saved project')
