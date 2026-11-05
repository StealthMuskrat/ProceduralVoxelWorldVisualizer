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
assert all(b[k] is True for k in [
    'duplicateOpenPreserved', 'refreshFailureConsumed', 'closedDrawConsumed',
    'staleSourcePreserved', 'oversizedRetry', 'rawSeedPreserved'])
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

# Deferred background feedback receipt; production widgets share the expanded probe context.
feedback = json.loads((expanded / 'background-expression-native.json').read_text())
assert feedback['formatVersion'] == 1
cases = {case['id']: case for case in feedback['cases']}
assert len(feedback['cases']) == len(cases) == 7
assert set(cases) == {'queued-and-held', 'inline-unicode-coalescing',
                      'expanded-current-span', 'schedule-retry', 'handoff-retry', 'inline-handoff-retry', 'escape-and-close'}
held = cases['queued-and-held']
assert held['queuedFrames'] >= 12 and held['heldFrames'] >= 12
assert held['launches'] == 1 and held['displayOnly'] is True
inline_case = cases['inline-unicode-coalescing']
assert inline_case['quietFrames'] >= 12 and inline_case['oldSpanRetired'] is True
assert (inline_case['utf16Start'], inline_case['utf16End']) == (2, 9)
assert (inline_case['utf8Start'], inline_case['utf8End']) == (3, 10)
expanded_case = cases['expanded-current-span']
assert (expanded_case['utf8Start'], expanded_case['utf8End']) == (4, 11)
assert expanded_case['exactText'] == 'é+\nbias'
assert expanded_case['pendingUseText'] is expanded_case['privateStage'] is True
for key in ['schedule-retry', 'handoff-retry', 'inline-handoff-retry']:
    assert cases[key]['busyFrames'] >= 24
    assert cases[key]['explicitRetry'] is cases[key]['lateCancelIgnored'] is True
    assert cases[key]['inlineFailureObserved'] is (key == 'inline-handoff-retry')
    assert cases[key]['failures'] == 1
assert all(cases['escape-and-close'][key] is True for key in [
    'escapePreservedDraft', 'successorClosed', 'lateCompletionIgnored'])
print('EXPRESSION_SELECTION_INDEPENDENT_PASS Unicode / multiline / one-shot / private buffer / saved project')
print('BACKGROUND_EXPRESSION_REPLAY_PASS held delivery / coalescing / Unicode / private pending stage / retry / closure')
