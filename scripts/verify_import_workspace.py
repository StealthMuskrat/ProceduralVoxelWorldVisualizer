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
