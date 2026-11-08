"""Deferred independent whole-project, references and native identity-review expectations."""
import copy
import json
from pathlib import Path
import sys

out = Path(sys.argv[1])
def read(name):
    return json.loads((out / (name + '.json')).read_text(encoding='utf-8'))

before = read('before')
assert [t['id'] for t in before['voxelTypes']] == ['stone', 'snow']
assert len(before['voxelGroups']) == 2048 and len(before['voxelSets']) == 1
stone = before['voxelTypes'][0]['uuid']
for group in before['voxelGroups']:
    assert group['voxelTypeUuids'] == [stone]
assert before['voxelSets'][0]['voxelTypeUuids'] == [t['uuid'] for t in before['voxelTypes']]
layer = before['worldGenerator']['layers'][0]
assert layer['defaults']['voxelTypeUuid'] == stone and layer['defaults']['action'] == 'SET'
assert layer['operations'][0]['overrides']['voxelTypeUuid'] == stone
assert before['noise2D'][0]['algorithm']['parameters'] == {'value': 1}
for name, expected_id in [('properties', 'granite'), ('properties-undone', 'stone'),
                          ('properties-redone', 'granite'), ('inline', 'marble'),
                          ('inline-undone', 'granite'), ('inline-redone', 'marble')]:
    expected = copy.deepcopy(before)
    expected['voxelTypes'][0]['id'] = expected_id
    assert read(name) == expected, name
assert read('preferences-before') == read('preferences')
r = read('voxel-identity-native')
assert r['formatVersion'] == 1 and r['initialBuilds'] == 1
assert r['type'] == stone and r['referenceRequests'] == [stone, stone]
assert r['privatePreserved'] and r['duplicateRejected'] and r['managerEscape']
assert r['physicalDpiVerified'] is False
expected_dependents = [(g['uuid'], 'group', g['id']) for g in before['voxelGroups']]
expected_dependents += [(before['voxelSets'][0]['uuid'], 'set', 'solid-set'),
                        (layer['uuid'], 'layer', 'ground'),
                        (layer['operations'][0]['uuid'], 'operation', 'fill')]
assert [(d['uuid'], d['kind'], d['id']) for d in r['dependents']] == expected_dependents
assert len(r['cases']) == 6
for i in range(3):
    for host in ('manager', 'properties'):
        case = next(c for c in r['cases'] if c['scenario'] == i and c['host'] == host)
        assert case['scale'] == (1, 2.5, 6.25)[i]
        assert 0 < case['submitted'] <= 40
        b, a = case['bounds'], case['action']
        assert b['width'] > 0 and b['height'] > 0
        assert a['width'] > 0 and a['height'] > 0
        assert b['x'] - 1 <= a['x'] and b['y'] - 1 <= a['y']
        assert a['x'] + a['width'] <= b['x'] + b['width'] + 1
        assert a['y'] + a['height'] <= b['y'] + b['height'] + 1
long_ids = ['a' * 257, 'x' * 255 + 'é界😀', 'é界😀' * 2049]
inline_suffix = '-inline-' + 'z' * 300
properties_suffix = '-properties-' + '😀' * 80
for i, original in enumerate(long_ids):
    inline_id = original + inline_suffix
    properties_id = inline_id + properties_suffix
    for phase, expected_id in [('before', original), ('unchanged', original),
                               ('inline', inline_id), ('inline-undone', original),
                               ('inline-redone', inline_id), ('properties', properties_id),
                               ('properties-undone', inline_id), ('properties-redone', properties_id)]:
        expected = copy.deepcopy(before)
        expected['voxelTypes'][0]['id'] = expected_id
        assert read(f'long-{i}-{phase}') == expected, (i, phase)
print('Voxel identity independent PASS; six native layouts, 2051 exact dependents, three long-ID cases/24 whole-project readbacks, UUID-preserving rename/Undo/Redo/project/preferences replay')
