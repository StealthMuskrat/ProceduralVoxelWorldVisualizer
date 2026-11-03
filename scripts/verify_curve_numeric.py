"""Deferred independent local numeric gesture and complete parent publication replay."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('curve-numeric-native.json')
assert report['formatVersion'] == 2 and report['physicalInputVerified'] is False
assert report['exactTextBits'] == '3ff0000000000001'
for flag in ('oneLocalUndo', 'escapeHeldCancelled', 'collapseCancelled', 'privateStaging', 'oneProjectUndo'):
    assert report[flag] is True
slider = report['sliderValue']
assert math.isfinite(slider) and -.2 <= slider <= 1.2 and slider != .5
assert slider != round(slider * 100000) / 100000
before = read('before.json')
noise, = before['noise2D']
item, = noise['processing']
assert item['kind'] == 'CURVE' and item['inputs']['a']['sourceKind'] == 'Ambient Value'
assert item['inputs']['a']['ambientKey'] == 'x'
assert noise['outputBinding']['itemUuid'] == item['uuid']
curve = item['parameters']['curve']
assert curve['min'] == 0 and curve['max'] == 1 and curve['outsideDomain'] == 'CLAMP'
assert [(p['x'], p['y']) for p in curve['points']] == [(0, 0), (.5, .5), (1, 1)]
assert all(p['segmentInterpolation'] == 'LINEAR' for p in curve['points'])
assert read('local-before.json') == read('local-undo.json') == curve
dragged = deepcopy(curve)
dragged['points'][1]['y'] = slider
for name in ('local-dragged', 'local-redo', 'local-escape', 'local-collapse'):
    assert read(name + '.json') == dragged, name
expected = deepcopy(before)
expected['noise2D'][0]['processing'][0]['parameters']['curve']['points'][1]['y'] = .75
assert read('private-staged.json') == expected['noise2D'][0]
assert read('applied.json') == read('redone.json') == expected
assert read('undone.json') == before
assert read('preferences-before.json') == read('preferences.json')
print('Curve numeric one-step local history, held cancellation, exact text and complete parent publication PASS')

# Deferred context Help replay is observational before the existing numeric edit sequence.
expected_guidance = {(key, part, 'curve.editor.' + key)
                     for key in ('min', 'displayMin', 'x', 'y')
                     for part in ('slider', 'text')}
expected_guidance |= {(key, 'control', 'curve.editor.' + key)
                      for key in ('outsideDomain', 'segmentInterpolation', 'fit', 'apply', 'cancel')}
assert {(row['key'], row['part'], row['topic']) for row in report['guidance']} == expected_guidance
assert len(report['guidance']) == 13
for key in ('min', 'displayMin', 'x', 'y'):
    copied = (out / ('curve-help-' + key + '.txt')).read_text(encoding='utf-8')
    assert 'Curve fixture 100% ### snow 雪 / ' + key in copied
    assert 'Topic: curve.editor.' + key in copied
    assert 'help.curve-guidance' in copied
