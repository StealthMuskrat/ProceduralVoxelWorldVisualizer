"""Deferred independent tree geometry, authored context and operation-only package replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'export-hierarchy-native.json').read_text(encoding='utf-8'))
source = json.loads((out / 'source-project.json').read_text(encoding='utf-8'))
package = json.loads((out / 'hierarchy.pvresources.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == package['packageVersion'] == 1
assert report['fontSource'] == 'user-scale-fixture'
for key in ('searchContext', 'selectedOnlyContext', 'expansionRestored', 'disclosureOnly',
            'folderSubtreeSelection', 'exactIncludedContext', 'operationOnlyPackage', 'privateStatePreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
layers = source['worldGenerator']['layers']
assert [layer['id'] for layer in layers] == ['unrelated', 'carrier']
chosen = layers[1]['operations'][0]['uuid']
assert layers[1]['operations'][0]['id'] == 'chosen' and len(layers[1]['operations']) == 2
assert len(source['noise2D']) == 502
payload = package['resources']
assert [n['id'] for n in payload['noise2D']] == ['signal']
assert payload['noise2D'][0] == source['noise2D'][0]
assert not payload['noise3D'] and [t['id'] for t in payload['voxelTypes']] == ['solid']
assert len(payload['worldGenerator']['layers']) == 1
carrier = payload['worldGenerator']['layers'][0]
expected_carrier = dict(layers[1])
expected_carrier['operations'] = layers[1]['operations'][:1]
assert carrier == expected_carrier
assert carrier['defaults']['action'] == 'SET' and carrier['defaults']['threshold'] == .5
assert carrier['operations'][0]['overrides'] == {}
assert payload['noise2D'][0]['algorithm']['parameters'] == {'value': .625}
source_folders = {f['id']: f for f in source['folders']}
folders = {f['id']: f for f in payload['folders']}
assert set(folders) == {'fields', 'nested', 'world-folder', 'deep-world'}
ids = {f['uuid'] for f in payload['folders']}
ids.update(n['uuid'] for n in payload['noise2D'])
ids.update(t['uuid'] for t in payload['voxelTypes'])
ids.add(carrier['uuid']); ids.add(chosen)
for name, folder in folders.items():
    expected = dict(source_folders[name])
    expected['childOrder'] = [id_ for id_ in expected['childOrder'] if id_ in ids]
    assert folder == expected
assert payload['project']['rootOrder']['noise2D'] == [folders['fields']['uuid']]
assert payload['project']['rootOrder']['world'] == [folders['world-folder']['uuid']]
assert carrier['parentFolderUuid'] == folders['deep-world']['uuid']
assert folders['deep-world']['parentFolderUuid'] == folders['world-folder']['uuid']
assert payload['noise2D'][0]['parentFolderUuid'] == folders['nested']['uuid']
assert folders['nested']['parentFolderUuid'] == folders['fields']['uuid']
assert len(report['cases']) == 12
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in ('Choices', 'Included')}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i = case['scenario']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['work'], case['modal']); contains(case['modal'], case['body'])
    contains(case['body'], case['list'])
    actions = case['actions']
    order = (('submit', 'cancel', 'reload', 'remove-unavailable'), ('choices', 'included', 'location'))
    assert set(actions) == {key for row in order for key in row}
    bottom = case['body']['y'] + case['body']['height']
    for row in order:
        for key in row:
            contains(case['modal'], actions[key]); assert actions[key]['y'] >= bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(actions[left]['y'] - actions[right]['y']) <= .1
            assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .1
        bottom = max(actions[key]['y'] + actions[key]['height'] for key in row)
    assert case['stride'] == math.ceil(case['metrics']['frame'] + case['metrics']['gap'])
    assert 0 < case['rows'] <= math.ceil(case['list']['height'] / case['stride']) + 2
    if case['form'] == 'Choices':
        assert case['rows'] <= 4
        assert case['ids'][chosen] == report['stableOperationId'] and case['depths'][chosen] == 3
    else:
        assert case['rows'] <= 3 and case['ids'] == {}
        assert set(case['depths']).issubset({folders['fields']['uuid'], folders['world-folder']['uuid'], payload['voxelTypes'][0]['uuid']})
print('Export hierarchy native geometry, exact carrier/folder order and operation-only dependency replay PASS')
