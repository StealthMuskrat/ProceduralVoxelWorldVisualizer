"""Deferred independent curve-pointer report and saved knot expectations."""
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
report = json.loads((root / 'curve-gesture-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('collapseRollback', 'appFocusRollback', 'privateStage', 'cancel', 'escape',
            'altBypass', 'oneGestureUndo', 'oneDocumentUndoRedo', 'duplicateOpenPreserved',
            'stageFailureRetry', 'refreshFailureConsumed', 'staleSourcePreserved',
            'sourceTokenConsumed', 'rawSeedPreserved'):
    assert report[key] is True, key
assert report['before'] == .25 and report['after'] == .35 and report['points'] == 4
project = json.loads((root / 'curve-gesture-project.json').read_text(encoding='utf-8'))
noise = project['noise2D'][0]
item = noise['processing'][0]
curve = item['parameters']['curve']
expected = [(0, 0), (.25, .35), (.75, .85), (1, 1)]
assert len(curve['points']) == len(expected)
for point, (x, y) in zip(curve['points'], expected):
    assert abs(point['x'] - x) < 2e-6
    assert abs(point['y'] - y) < 2e-6
assert noise['outputBinding']['itemUuid'] == item['uuid']
assert curve['outsideDomain'] == 'CLAMP'
print('Curve pointer interruption report, independent knots and output ownership PASS')
