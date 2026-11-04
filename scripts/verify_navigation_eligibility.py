"""Deferred independent Find/reference eligibility routes, exact edits and native modal receipts."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys
import jsonschema

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('navigation-eligibility-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['privateEditorPreserved'] is True and report['oneUndoRestored'] is True
expected = [('find-rejected', 0, True, False), ('reference-rejected', 0, True, False),
            ('find-blocked-apply', 0, True, True), ('find-blocked-discard', 0, True, True),
            ('find-blocked-cancel', 0, True, True), ('find-apply', 1, False, False),
            ('find-discard', 0, False, False), ('reference-apply', 1, False, False),
            ('reference-discard', 0, False, False)]
assert len(report['routes']) == len(expected)
for row, (phase, revision, raw, private) in zip(report['routes'], expected):
    assert row == dict(phase=phase, revisionDelta=revision, rawRetained=raw,
                       privateEditor=private, pending=False)
cases = report['cases']
assert [(row['form'], row['scenario']) for row in cases] == [(form, i) for form in ('find', 'reference') for i in range(6)]
ids = {}
for row in cases:
    form, i = row['form'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    assert set(row['actions']) == set(row['ids']) == {'APPLY', 'DISCARD', 'CANCEL'}
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(form, row['ids'])
    assert ids[form] == row['ids']
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
source = read('source-project.json')
expected_project = deepcopy(source)
expected_project['noise2D'][0]['id'] = 'private-field'
for form in ('find', 'reference'):
    assert read(form + '-applied.json') == expected_project
    assert read(form + '-undo.json') == source
    trace = read(form + '-trace.json')
    assert trace['position'] == dict(x=2, y=0, z=3) and trace['finalType'] == 1
    assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
    assert all(step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22 for step in trace['steps'])
assert read('after.json') == source
assert read('preferences-before.json') == read('preferences.json')
# Deferred background Apply across actual source, Find and reference decision controls.
background = out / 'background-apply'
schema = json.loads((Path(__file__).resolve().parent.parent / 'schema/project.schema.json').read_text(encoding='utf-8'))
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
background_report = json.loads((background / 'background-navigation.json').read_text(encoding='utf-8'))
assert background_report['formatVersion'] == 1 and background_report['physicalInputVerified'] is False
routes = [(route, mode) for route in ('source', 'find', 'reference')
          for mode in (['success', 'cancel', 'escape', 'retry', 'stale', 'after-stale', 'close']
                       if route == 'source' else ['success', 'cancel'])]
assert [(case['route'], case['mode']) for case in background_report['cases']] == routes
snapshots = 0
for case in background_report['cases']:
    route, mode = case['route'], case['mode']
    applied = mode in ('success', 'retry', 'after-stale')
    assert case['applied'] is applied and case['oneUndoRedo'] is applied
    assert case['navigated'] is (applied and mode != 'after-stale')
    assert case['responsiveFrames'] == 12
    assert all(case[key] is True for key in ('privateUntilPublication', 'preferencesRetained', 'originalBytesRetained'))
    directory = background / (route + '-' + mode)
    baseline = json.loads((directory / 'before.json').read_text(encoding='utf-8'))
    assert [noise['id'] for noise in baseline['noise2D']] == ['source', 'destination']
    assert baseline['noise2D'][0]['algorithm']['parameters']['value'] == .125
    assert baseline['noise2D'][1]['algorithm']['parameters']['value'] == .375
    assert baseline['worldGenerator']['layers'] == []
    assert json.loads((directory / 'preferences-before.json').read_text(encoding='utf-8')) == json.loads((directory / 'preferences.json').read_text(encoding='utf-8'))
    for name in (['before', 'after', 'undone', 'redone'] if applied else ['before', 'after']):
        saved = json.loads((directory / (name + '.json')).read_text(encoding='utf-8'))
        validator.validate(saved)
        expected_project = deepcopy(baseline)
        expected_project['noise2D'][0]['algorithm']['parameters']['value'] = .625 if applied and name in ('after', 'redone') else .125
        assert saved == expected_project
        snapshots += 1
assert snapshots == 32
assert json.loads((background / 'engine-replay.json').read_text(encoding='utf-8')) == dict(formatVersion=1, snapshots=32, fields=1344, cells=9408, roots=[2, 4])
print('Find/reference eligibility and background source/Find/reference Apply, exact saved states, Undo/Redo and independent engine replay PASS')
