"""Deferred independent expanded-expression geometry, owned ranges and committed fixture replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'expression-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('persistentCancel', 'unicodeErrorReveal', 'cancelBeforeStage', 'refreshConsumed',
            'oversizeRetry', 'staleOwner', 'namedInputFeedback', 'incompleteTextStaged', 'projectPreserved'):
    assert report[key] is True, key
for key in ('internalMultilineFontVerified', 'physicalDpiVerified', 'physicalDetachedVerified',
            'gpuFontTextureVerified', 'imeVerified'):
    assert report[key] is False, key
assert report['acceptedStages'] == 3 and report['finalPrivateText'] == 'x+'
assert len(report['cases']) == 6
assert [case['scenario'] for case in report['cases']] == list(range(6))
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


widget = None
for case in report['cases']:
    i = case['scenario']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    assert case['selection'] == {'start': 3, 'end': 10}
    if widget is None:
        widget = case['widgetId']
    assert case['widgetId'] == widget and widget != 0
    modal, body, actions = case['modal'], case['body'], case['actions']
    contains(case['work'], modal)
    contains(modal, body)
    assert set(actions) == {'stage', 'cancel', 'selectError'}
    for rect in actions.values():
        contains(modal, rect)
        assert rect['y'] >= body['y'] + body['height'] - .1
    assert abs(actions['stage']['y'] - actions['cancel']['y']) <= .1
    assert actions['stage']['x'] + actions['stage']['width'] <= actions['cancel']['x'] + .1
    assert actions['selectError']['y'] >= actions['stage']['y'] + actions['stage']['height'] - .1
    assert actions['selectError']['width'] > actions['stage']['width']
    editor, feedback = case['editor'], case['feedback']
    for rect in (editor, feedback):
        assert all(math.isfinite(v) for v in rect.values()) and rect['width'] > 0 and rect['height'] > 0
        assert rect['x'] >= body['x'] - .1
        assert rect['x'] + rect['width'] <= body['x'] + body['width'] + .1
    line = case['line']
    assert line > 0 and math.isfinite(line)
    assert abs(editor['height'] - max(1, min(.5 * body['height'], 8 * line))) <= 1.1
    assert abs(feedback['height'] - max(1, min(.3 * body['height'], 4 * line))) <= 1.1
    assert editor['y'] < body['y'] + body['height'] and editor['y'] + editor['height'] > body['y']
project = json.loads((out / 'expression-layout-project.json').read_text(encoding='utf-8'))
noise, = project['noise2D']
item, = noise['processing']
assert noise['id'] == 'formula' and noise['outputBinding']['itemUuid'] == item['uuid']
assert item['kind'] == 'EXPRESSION' and item['parameters']['expression'] == 'x+bias'
assert item['inputs']['bias']['sourceKind'] == item['inputs']['é']['sourceKind'] == 'Constant'
assert item['inputs']['bias']['value'] == 2 and item['inputs']['é']['value'] == 0
print('Expanded expression persistent geometry, Unicode range ownership and unchanged committed formula PASS')
