"""Deferred independent exact document review, geometry and complete import/package replay."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys
from uuid import UUID

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('resource-review-lifetime-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['importValidations'] == report['exportWrites'] == report['exportReplacements'] == 1
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
    keys = ({'apply', 'cancel', 'reload', 'validation', 'resources', 'filters', 'issues'}
            if phase.startswith('import') else
            {'submit', 'cancel', 'reload', 'remove-unavailable', 'choices', 'included', 'location'})
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
print('Resource review lifetime, seven phases, 24 native receipts, one exact import/Undo, noise-only package and original preferences PASS')
