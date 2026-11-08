"""Deferred independent exact document review, geometry and complete import/package replay."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys
import jsonschema
from uuid import UUID

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('resource-review-lifetime-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['importValidations'] == 2
assert report['exportWrites'] == report['exportReplacements'] == 1
assert report['deliveryRoutes'] == ['import-held', 'import-escape', 'validation-successor',
                                    'validation-throw', 'validation-nested', 'export-held', 'export-escape']
assert report['eligibilityCallbacksOutsidePopup'] > 0
assert report['publicationCallbacksAfterPopup'] == 4
assert report['validationCallbacksAfterPopup'] == 3
expected = [('import-current', True, True, 1), ('import-reopened', False, False, 1),
            ('import-committed', False, False, 2), ('import-undone', False, False, 1),
            ('export-current', True, True, 1), ('export-reopened', False, False, 1),
            ('export-written', False, False, 1)]
assert report['phases'] == [dict(phase=phase, current=current, canReload=reload, noiseCount=count)
                            for phase, current, reload, count in expected]
forms = ('import-current', 'import-reopened', 'export-current', 'export-reopened')
assert [(row['phase'], row['scenario']) for row in report['cases']] == [
    (phase, i) for phase in forms for i in range(6)]
ids = {}
for row in report['cases']:
    phase, i = row['phase'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    keys = ({'apply', 'cancel', 'reload', 'validation', 'retry', 'resources', 'filters', 'issues',
             'undo', 'redo'}
            if phase.startswith('import') else
            {'submit', 'cancel', 'reload', 'remove-unavailable', 'choices', 'included', 'location',
             'undo', 'redo'})
    assert set(row['actions']) == set(row['ids']) == keys
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(phase, row['ids'])
    assert ids[phase] == row['ids']
    assert all(math.isfinite(value) for value in row['window'].values())
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1

source, incoming = read('source-project.json'), read('incoming.json')
assert len(source['noise2D']) == len(incoming['noise2D']) == 1
assert source['noise2D'][0]['id'] == 'field'
assert incoming['noise2D'][0]['id'] == 'added'
assert incoming['noise2D'][0]['algorithm']['parameters']['value'] == .75
assert str(UUID(report['copiedTarget'])) == report['copiedTarget']
assert report['incoming'] == incoming['noise2D'][0]['uuid'] != report['copiedTarget']
assert report['copiedTarget'] != source['noise2D'][0]['uuid']
expected_import = deepcopy(source)
added = deepcopy(incoming['noise2D'][0])
added['uuid'] = report['copiedTarget']
expected_import['noise2D'].append(added)
# Import appends explicit root presentation order while retaining execution order and metadata.
expected_import['project']['rootOrder']['noise2D'] = [n['uuid'] for n in expected_import['noise2D']]
expected_import['project']['rootOrder']['world'] = [l['uuid'] for l in source['worldGenerator']['layers']]
assert read('imported-project.json') == expected_import
assert read('after.json') == read('import-undo.json') == source
assert read('preferences-before.json') == read('preferences.json')

package = read('selection.pvresources.json')
assert set(package) == {'packageVersion', 'resources'} and package['packageVersion'] == 1
portable = package['resources']
assert portable['formatVersion'] == 2 and portable['noise2D'] == source['noise2D']
assert all(not portable[key] for key in ('noise3D', 'voxelTypes', 'voxelGroups', 'voxelSets', 'folders'))
assert not portable['worldGenerator']['layers'] and all(not values for values in portable['bookmarks'].values())
assert portable['project']['uuid'] != source['project']['uuid']
assert portable['project']['seed'] == 0 and portable['project']['rootOrder'] == {}
assert portable['project']['minY'] == -64 and portable['project']['maxY'] == 319
assert not (out / 'stale-must-not-write.pvresources.json').exists()
assert not (out / 'nested-must-not-write.pvresources.json').exists()

assert not (out / 'escape-must-not-write.pvresources.json').exists()
assert not (out / 'held-must-not-write.pvresources.json').exists()
# Deferred worker membership Apply through the actual Properties entry and member controls.
background = out / 'background-membership'
receipt = json.loads((background / 'background-membership.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
routes = [(kind, mode) for kind in ('set', 'group')
          for mode in (['success', 'cancel', 'escape', 'retry', 'edit', 'raw', 'close', 'empty', 'ready-cancel', 'ready-escape', 'ready-edit']
                       if kind == 'set' else ['success', 'empty'])]
assert [(case['kind'], case['mode']) for case in receipt['cases']] == routes
schema = json.loads((Path(__file__).resolve().parent.parent / 'schema/project.schema.json').read_text(encoding='utf-8'))
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
snapshots = 0
for case in receipt['cases']:
    kind, mode = case['kind'], case['mode']
    applied = mode in ('success', 'retry', 'edit', 'empty')
    assert case['applied'] is applied and case['oneUndoRedo'] is applied
    assert case['responsiveFrames'] == 12
    assert case['readyRetired'] is mode.startswith('ready-')
    assert case['members'] == (0 if mode == 'empty' else 2 if applied else 1)
    assert all(case[key] is True for key in ('privateIdSeedRetained', 'preferencesRetained', 'originalBytesRetained'))
    directory = background / (kind + '-' + mode)
    baseline = json.loads((directory / 'before.json').read_text(encoding='utf-8'))
    solid, spare, third = [item['uuid'] for item in baseline['voxelTypes']]
    assert [item['id'] for item in baseline['voxelTypes']] == ['solid', 'spare', 'third']
    assert baseline['project']['seed'] == 5 and baseline['project']['minY'] == -2 and baseline['project']['maxY'] == 2
    member_set, = baseline['voxelSets']
    group, = baseline['voxelGroups']
    assert member_set['id'] == 'members' and group['id'] == 'group'
    assert member_set['voxelTypeUuids'] == group['voxelTypeUuids'] == [spare]
    noise, = baseline['noise2D']
    assert noise['id'] == 'field' and noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': .125}
    fill, clear = baseline['worldGenerator']['layers']
    assert fill['defaults']['action'] == 'SET' and fill['defaults']['voxelTypeUuid'] == solid
    assert clear['defaults']['action'] == 'CLEAR'
    assert clear['defaults']['voxelStatePredicate'] == dict(kind='IN_SET', reference=member_set['uuid'], children=[])
    assert all(len(layer['operations']) == 1 and layer['operations'][0]['interpretation'] == 'ALL'
               and layer['operations'][0]['noiseGeneratorUuid'] == noise['uuid'] for layer in (fill, clear))
    assert json.loads((directory / 'preferences-before.json').read_text(encoding='utf-8')) == json.loads((directory / 'preferences.json').read_text(encoding='utf-8'))
    for name in (['before', 'after', 'undone', 'redone'] if applied else ['before', 'after']):
        saved = json.loads((directory / (name + '.json')).read_text(encoding='utf-8'))
        validator.validate(saved)
        target = applied and name in ('after', 'redone')
        expected = deepcopy(baseline)
        values = [spare] if not target else [] if mode == 'empty' else [spare, third if mode == 'edit' else solid]
        expected['voxelSets' if kind == 'set' else 'voxelGroups'][0]['voxelTypeUuids'] = values
        expected['project']['seed'] = 6 if mode == 'raw' and name == 'after' else 5
        assert saved == expected
        snapshots += 1
assert snapshots == 38
assert json.loads((background / 'engine-replay.json').read_text(encoding='utf-8')) == dict(formatVersion=1, snapshots=38, fields=798, cells=11172, solidCells=7140, roots=[2, 4])
print('Resource review lifetime and background members-only Apply: exact saved state, set/group semantics, private input retention, native and engine replay PASS')
