"""Deferred independent production numeric layout, complete project and preference replay."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('property-numeric-native.json')
assert report['formatVersion'] == 1
assert report['fontSource'] == 'synthetic-global-font-fixture'
assert report['physicalDpiVerified'] is False
for flag in ('exactFocusEdited', 'outsideSuggestionEdited', 'privateUntilApply', 'oneUndoRedo'):
    assert report[flag] is True, flag
guidance = report['numericGuidance']
expected_topics = {
    'source': 'noise2D.algorithm.CONSTANT.value',
    'transform': 'transform2D.TRANSLATE.x',
    'warp': 'processing.WARP_SAMPLE.parameter.amplitude',
    'remap': 'processing.REMAP.parameter.inputMin',
}
assert len(guidance) == 8
assert {(g['role'], g['part']) for g in guidance} == {
    (role, part) for role in expected_topics for part in ('slider', 'text')}
for role, topic in expected_topics.items():
    pair = [g for g in guidance if g['role'] == role]
    assert len({g['nativeId'] for g in pair}) == 2
    for g in pair:
        assert g['topic'] == topic
        assert g['configuredTooltip'] == 'Numeric fixture 100% ### snow 雪 / ' + role
    help_text = (out / ('numeric-help-' + role + '.txt')).read_text(encoding='utf-8')
    assert 'Topic: ' + topic in help_text
    assert pair[0]['configuredTooltip'] in help_text
value = report['sliderValue']
assert math.isfinite(value) and -1 <= value <= 1
assert value != round(value * 100000) / 100000
roles = ('source', 'transform', 'warp', 'remap', 'scalar', 'vector', 'parameter')
cases = report['cases']
assert len(cases) == 84
assert {(c['dimension'], c['role'], c['scenario']) for c in cases} == {
    (d, role, i) for d in (2, 3) for role in roles for i in range(6)}
identities = {}
presentations = set()


def rectangle(box):
    assert set(box) == {'x', 'y', 'width', 'height'}
    assert all(math.isfinite(n) for n in box.values())
    assert box['width'] > 0 and box['height'] > 0


for case in cases:
    assert case['scale'] == (1, .5, 1, 2.5, 6.25, 1)[case['scenario']]
    body = case['body']
    rectangle(body)
    assert case['rows']
    ids = []
    for row in case['rows']:
        slider, text = row['slider'], row['input']
        for box in (slider, text):
            rectangle(box)
            # Offscreen vertical positions inside the scrolling body are not visibility claims.
            assert box['x'] >= body['x'] - 1.1
            assert box['x'] + box['width'] <= body['x'] + body['width'] + 1.1
        assert math.isfinite(row['minimum']) and math.isfinite(row['maximum'])
        assert row['minimum'] <= row['maximum']
        presentations.add(row['inline'])
        if row['inline']:
            assert abs(slider['y'] - text['y']) <= 1.1
            assert slider['x'] + slider['width'] <= text['x'] + 1.1
        else:
            assert abs(slider['x'] - text['x']) <= 1.1
            assert slider['y'] + slider['height'] <= text['y'] + 1.1
        ids.extend((row['sliderId'], row['inputId']))
    assert all(ids) and len(ids) == len(set(ids))
    key = (case['dimension'], case['role'])
    if key not in identities:
        identities[key] = set(ids)
    assert set(ids) == identities[key]
assert presentations == {True, False}

before = read('before.json')
assert len(before['noise2D']) == len(before['noise3D']) == 1
for key, dimension in (('noise2D', 2), ('noise3D', 3)):
    noise = before[key][0]
    assert noise['id'] == f'numeric-{dimension}' and noise['dimension'] == dimension
    assert noise['algorithm'] == {'key': 'CONSTANT', 'parameters': {'value': .125}}
    assert noise['sharedParameters'][0]['id'] == 'parameter'
    assert noise['sharedParameters'][0]['type'] == 'Scalar'
    assert noise['sharedParameters'][0]['value'] == .25
    assert len(noise['coordinateTransforms']) == 1
    assert noise['coordinateTransforms'][0]['kind'] == 'TRANSLATE'
    assert [(i['id'], i['kind']) for i in noise['processing']] == [
        ('warp', 'WARP_SAMPLE'), ('remap', 'REMAP'), ('scalar', 'CONSTANT'), ('vector', 'CONSTANT')]
    assert noise['processing'][-1]['resultType'] == f'Vector{dimension}'
    assert noise['processing'][-1]['parameters']['value'] == [0] * dimension
    assert noise['outputBinding']['sourceKind'] == 'Source Result'
    assert noise['outputBinding']['type'] == 'Scalar'
expected = deepcopy(before)
expected['noise2D'][0]['algorithm']['parameters']['value'] = .625
expected['noise2D'][0]['sharedParameters'][0]['value'] = .75
assert read('applied.json') == read('redone.json') == expected
assert read('undone.json') == before
assert read('preferences-before.json') == read('preferences.json')
print('Property numeric paired/stacked geometry, stable IDs, exact focus and complete Apply/Undo/persistence PASS')
