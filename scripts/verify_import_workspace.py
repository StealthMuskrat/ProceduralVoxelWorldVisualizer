"""Deferred independent schema and saved-model oracle for the native import workspace."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
schema = json.loads((repo / 'schema/project.schema.json').read_text())
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
before = json.loads((out / 'import-before.json').read_text())
after = json.loads((out / 'import-after.json').read_text())
package = json.loads((out / 'import-package.json').read_text())
targets = json.loads((out / 'import-targets.json').read_text())
report = json.loads((out / 'import-workspace-native.json').read_text())
for project in [before, after, package['resources']]:
    validator.validate(project)
assert package['packageVersion'] == 1 and len(package['resources']['noise2D']) == 1004
old = {n['uuid']: n for n in before['noise2D']}
new = {n['uuid']: n for n in after['noise2D']}
assert len(old) == 3 and len(new) == 5 and all(new[key] == value for key, value in old.items())
expected = {'editing': .125, 'shared': .25, 'conflict': .5, 'conflict-copy': .9, 'retained': .75}
by_id = {n['id']: n for n in after['noise2D']}
assert set(by_id) == set(expected)
assert all(n['seedOffset'] == 0 and n['algorithm']['key'] == 'CONSTANT' and n['algorithm']['parameters'] == {'value': expected[n['id']]} for n in new.values())
assert by_id['shared']['uuid'] == targets['shared'] == targets['existingShared']
assert by_id['conflict-copy']['uuid'] == targets['conflict'] and by_id['retained']['uuid'] == targets['retained']
assert targets['retained'] != targets['beforeReloadRetained']
incoming_ids = {n['id']: n['uuid'] for n in package['resources']['noise2D']}
assert by_id['conflict-copy']['uuid'] != incoming_ids['conflict'] and by_id['retained']['uuid'] != incoming_ids['retained']
old_meta, new_meta = dict(before['project']), dict(after['project'])
old_meta.pop('rootOrder', None); new_meta.pop('rootOrder', None)
assert old_meta == new_meta and after['project']['seed'] == 42 and before['bookmarks'] == after['bookmarks']
assert not after['noise3D'] and not after['worldGenerator']['layers'] and not after['voxelTypes'] and not after['folders']
flags = ['cancelRetained', 'staleReload', 'privateDraftRetained', 'failedApplyRetained', 'oneUndoRedo', 'queriesReused']
assert set(report) == {'formatVersion', 'incomingRows', 'topRows', 'scrolledRows', *flags}
assert report['formatVersion'] == 1 and report['incomingRows'] == 1004
assert 0 < report['topRows'] <= 24 and 0 < report['scrolledRows'] <= 24
assert all(report[key] is True for key in flags)
print('IMPORT_WORKSPACE_INDEPENDENT_PASS exact COPY/REUSE/SKIP / preserved existing definitions / reload targets / schema / bounded native report')

# Deferred background preparation report and complete saved-model checks.
background = out / 'background-import'
loading = json.loads((background / 'background-import.json').read_text())
assert loading['formatVersion'] == 1 and loading['physicalInputVerified'] is False
assert [c['mode'] for c in loading['cases']] == ['success', 'cancel', 'escape', 'raw', 'retry', 'close']
for case in loading['cases']:
    mode = case['mode']
    assert case['imported'] is (mode in ['success', 'retry'])
    assert case['responsiveFrames'] == 12
    assert all(case[k] is True for k in ['privateRetained', 'preferencesRetained', 'originalBytesRetained'])
    folder = background / mode
    previous = json.loads((folder / 'before.json').read_text())
    retained = json.loads((folder / 'after.json').read_text())
    validator.validate(previous); validator.validate(retained)
    assert len(previous['noise2D']) == 1 and len(retained['noise2D']) == (2 if case['imported'] else 1)
    assert previous['noise2D'][0] == retained['noise2D'][0]
    assert previous['project']['seed'] == 17 and retained['project']['seed'] == (18 if mode == 'raw' else 17)
    retained['project']['seed'] = 17
    if case['imported']:
        assert retained['project']['rootOrder'] == {'noise2D': [n['uuid'] for n in retained['noise2D']]}
        added = retained['noise2D'].pop()
        incoming_package = json.loads((folder / '资源 package.pvresources.json').read_text())
        validator.validate(incoming_package['resources'])
        incoming = incoming_package['resources']['noise2D'][0]
        assert added['id'] == 'incoming' and added['uuid'] != incoming['uuid']
        added['uuid'] = incoming['uuid']
        assert added == incoming
        previous['project'].pop('rootOrder', None); retained['project'].pop('rootOrder', None)
    assert previous == retained
replay = json.loads((background / 'engine-replay.json').read_text())
assert replay == {'formatVersion': 1, 'fields': 294, 'cells': 3528, 'roots': [2, 4]}
print('BACKGROUND_IMPORT_INDEPENDENT_PASS owned snapshots / native cancel-retry / complete retained project / 294 fields / 3528 EMPTY cells')

# Deferred choice/reload background planning: independent full models and replay dimensions.
planning = out / 'planning'
planning_report = json.loads((planning / 'import-planning.json').read_text())
assert planning_report['formatVersion'] == 1 and planning_report['physicalInputVerified'] is False
assert [c['mode'] for c in planning_report['cases']] == ['choice', 'reload', 'cancel', 'escape', 'retry', 'close']
for case in planning_report['cases']:
    mode = case['mode']; imported = mode in ['choice', 'reload', 'retry']
    assert case == dict(mode=mode, imported=imported, responsiveFrames=12,
        privateRetained=True, preferencesRetained=True, originalBytesRetained=True, freshReviewRequired=True)
    directory = planning / mode
    before = json.loads((directory / 'before.json').read_text())
    after = json.loads((directory / 'after.json').read_text())
    incoming = json.loads((directory / 'incoming.json').read_text())
    for project in [before, after, incoming]:
        validator.validate(project)
    assert before['project']['seed'] == 17
    assert len(before['noise2D']) == 2 and len(after['noise2D']) == (3 if imported else 2)
    assert [n['id'] for n in before['noise2D']] == ['existing', 'shared']
    assert before['noise2D'] == after['noise2D'][:2]
    assert [n['id'] for n in incoming['noise2D']] == ['shared', 'added']
    if mode == 'reload': before['project']['seed'] = 18
    if imported:
        added = after['noise2D'].pop()
        assert added['id'] == 'added' and added['uuid'] != incoming['noise2D'][1]['uuid']
        authored = dict(incoming['noise2D'][1]); authored['uuid'] = added['uuid']
        assert added == authored
        assert after['project']['rootOrder'] == {'noise2D': [n['uuid'] for n in before['noise2D']] + [added['uuid']]}
        for project in [before, after]: project['project'].pop('rootOrder', None)
    assert before == after
planning_replay = json.loads((planning / 'engine-replay.json').read_text())
assert planning_replay == dict(formatVersion=1, fields=567, cells=3528, roots=[2, 4])
print('IMPORT_PLANNING_INDEPENDENT_PASS six review lifetimes / complete saved models / 567 fields / 3528 EMPTY cells')

# Deferred final Import validation/publication receipts and complete saved-state checks.
apply = out / 'apply'
apply_report = json.loads((apply / 'import-apply-native.json').read_text())
assert apply_report['formatVersion'] == 1 and apply_report['physicalInputVerified'] is False
modes = {'success', 'cancel', 'escape', 'retry', 'rejected', 'stale', 'close'}
assert len(apply_report['cases']) == len(modes)
assert {case['mode'] for case in apply_report['cases']} == modes
for case in apply_report['cases']:
    mode = case['mode']; imported = mode in {'success', 'retry', 'rejected'}
    assert case['imported'] is imported
    assert case['queuedFrames'] >= 12 and case['heldFrames'] >= 12
    assert all(case[key] is True for key in [
        'privateRetained', 'preferencesRetained', 'copyIdentityRetained', 'originalBytesRetained'])
    directory = apply / mode
    before = json.loads((directory / 'before.json').read_text())
    after = json.loads((directory / 'after.json').read_text())
    incoming = json.loads((directory / 'incoming.json').read_text())
    for project in [before, after, incoming]: validator.validate(project)
    assert [n['id'] for n in before['noise2D']] == ['existing', 'shared']
    assert [n['id'] for n in incoming['noise2D']] == ['added']
    assert before['project']['seed'] == after['project']['seed'] == 17
    assert len(after['noise2D']) == (3 if imported else 2)
    assert before['noise2D'] == after['noise2D'][:2]
    if imported:
        added = after['noise2D'].pop()
        authored = dict(incoming['noise2D'][0])
        assert added['uuid'] != authored['uuid']
        authored['uuid'] = added['uuid']
        assert added == authored
        assert after['project']['rootOrder'] == {'noise2D': [n['uuid'] for n in before['noise2D']] + [added['uuid']]}
        for project in [before, after]: project['project'].pop('rootOrder', None)
    assert before == after
assert json.loads((apply / 'engine-replay.json').read_text()) == dict(
    formatVersion=1, fields=651, cells=4116, roots=[2, 4])
print('IMPORT_APPLY_INDEPENDENT_PASS seven final-Import lifetimes / exact saved models / 651 fields / 4116 EMPTY cells')
