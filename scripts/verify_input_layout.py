"""Deferred independent Input Settings geometry/stride/recording ownership receipt replay."""
import json
import math
from pathlib import Path
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'input-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic'
for key in ('ordinarySaveExcludedDraft', 'cancelBeforePoll', 'captureSuppressed', 'writeRetry', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert len(report['cases']) == 6
assert {case['scenario'] for case in report['cases']} == set(range(6))
sizes = ((1400, 1000), (300, 640), (260, 640), (260, 640), (720, 640), (1000, 300))
globals_ = (1, 1, 1.5, 2.5, 2.5, 1)
windows = (1, 1, 1, 1, 2.5, 1)
ids = set()
columns = set()
inline = set()


def contains(outer, inner):
    assert all(math.isfinite(value) for value in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .02
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .02


def strides(receipt):
    plan = receipt['plan']
    assert plan['rowHeight'] > 0 and math.isfinite(plan['rowHeight'])
    assert plan['rowHeight'] == math.floor(plan['rowHeight'])
    assert receipt['steps'] and set(receipt['steps']) == set(receipt['strides'])
    for key, step in receipt['steps'].items():
        assert 0 < step <= plan['rowHeight'] + .1
        assert 0 <= plan['rowHeight'] - step + .1 < 8
        assert abs(receipt['strides'][key] - plan['rowHeight']) <= .1


unfiltered = report['unfiltered']
assert 0 < unfiltered['submitted'] < unfiltered['total'] == 24
assert unfiltered['submitted'] <= math.ceil(unfiltered['list']['height'] / unfiltered['plan']['rowHeight']) + 2
strides(unfiltered)
ordinary = json.loads((out / 'ordinary-preferences.json').read_text(encoding='utf-8'))
original = ordinary['input']['shortcuts']
assert original['Find'] == 'Ctrl+F' and original['Save'] == 'Ctrl+S'
for case in report['cases']:
    i = case['scenario']
    width, height = sizes[i]
    assert case['window'] == dict(x=0, y=0, width=width, height=height)
    assert case['fontGlobalScale'] == globals_[i] and case['fontWindowScale'] == windows[i]
    contains(case['window'], case['category'])
    contains(case['window'], case['body'])
    assert case['list']['height'] <= .65 * case['body']['height'] + 1.1
    actions = case['actions']
    order = ('apply', 'revert', 'reset', 'show-problem', 'cancel-recording')
    assert set(actions) == set(order)
    for action in actions.values():
        contains(case['window'], action)
        assert action['y'] >= case['body']['y'] + case['body']['height'] - .02
    for left, right in zip(order, order[1:]):
        assert abs(actions[left]['y'] - actions[right]['y']) <= .02
        assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .02
    expected = dict(original)
    expected['Find'] = 'Ctrl+'
    assert case['raw'] == expected
    ids.add(case['findId'])
    plan = case['plan']
    columns.add(plan['actionColumns'])
    inline.add(plan['inline'])
    assert plan['actionRows'] == math.ceil(3 / plan['actionColumns'])
    row = case['row']
    order = ('text:Find', 'record:Find', 'unbind:Find', 'default:Find')
    assert set(row) == set(order)
    for key, rect in row.items():
        assert rect['width'] > 0 and rect['height'] > 0
        assert rect['x'] >= case['list']['x'] - .1
        assert rect['x'] + rect['width'] <= case['list']['x'] + case['list']['width'] + .1
    assert abs(row['text:Find']['width'] - plan['inputWidth']) <= 1.1
    for j, key in enumerate(order[1:]):
        assert abs(row[key]['width'] - plan['buttonWidth']) <= 1.1
        if plan['inline']:
            assert abs(row[key]['y'] - row['text:Find']['y']) <= .02
        else:
            assert row[key]['y'] >= row['text:Find']['y'] + row['text:Find']['height'] - .02
            if j and j % plan['actionColumns']:
                assert abs(row[key]['y'] - row[order[j]]['y']) <= .02
    strides(case)
assert len(ids) == 1 and columns == {1, 2, 3} and inline == {True, False}
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
expected = dict(original)
expected['Find'] = 'Ctrl+Alt+F8'
assert saved['input']['shortcuts'] == expected
assert saved['workspace']['width'] == 1234 and saved['appearance']['theme'] == 'Light'
print('Input row geometry/strides, exact raw IDs, ordinary save, cancel-before-poll and captured-key retry replay PASS')
