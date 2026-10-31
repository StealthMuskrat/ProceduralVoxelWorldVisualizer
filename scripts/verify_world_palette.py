"""Deferred independent source UUID/normal/ghost color and persisted-project replay checks."""
from copy import deepcopy
from pathlib import Path
import json
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('world-palette-native.json')
assert report['formatVersion'] == 1 and report['fixture'] == 'production-fixed-streamed-ghost-native-gl'
assert report['physicalDpiVerified'] is False and report['hardwareCertified'] is False
for key in ('manualFixedRetained', 'manualStreamRetained', 'latestFixedColors', 'latestSetupColors',
            'uuidReuseIgnored', 'undoUuidRestored', 'sourceGhostOffsetRetained', 'viewOnlyRepaint', 'privatePreserved'):
    assert report[key] is True, key
source, fixed, streamed, ghost = map(read, ('palette-source.json', 'fixed-after.json', 'stream-after.json', 'ghost-after.json'))
stone, other = [t['uuid'] for t in source['voxelTypes']]
red, green, blue, orange = (255, 0, 0), (0, 255, 0), (25, 55, 230), (210, 130, 30)
purple, cyan, violet = (180, 40, 200), (40, 180, 240), (160, 50, 200)
phases = [
    ('fixed-initial', [stone, other], [red, green], red),
    ('fixed-reordered', [stone, other], [red, green], red),
    ('fixed-recolored', [stone, other], [blue, orange], blue),
    ('fixed-reused-id', [stone, other], [blue, orange], blue),
    ('fixed-undo', [stone, other], [blue, orange], blue),
    ('fixed-late-accepted', [other, stone], [orange, purple], purple),
    ('stream-late-accepted', [other, stone], [orange, cyan], cyan),
    ('stream-reordered', [other, stone], [orange, cyan], cyan),
    ('stream-recolored', [other, stone], [orange, violet], violet),
    ('ghost-initial', [stone, other], [violet, orange], (235, 30, 66)),
    ('ghost-recolored', [stone, other], [cyan, orange], (235, 60, 80)),
]
observations = report['observations']
assert len(observations) == len(phases)
stamps, epochs = [], []
for observed, (phase, types, colors, pixel) in zip(observations, phases):
    assert observed['phase'] == phase and observed['palette']['types'] == types
    actual = observed['palette']['colors']
    assert len(actual) == 5
    for i, color in enumerate(colors):
        normal = actual[i + 1]
        ghost_color = actual[3 + i]
        assert tuple((normal >> shift) & 255 for shift in (0, 8, 16)) == color
        assert (normal >> 24) & 255 == 255
        assert tuple((ghost_color >> shift) & 255 for shift in (0, 8, 16)) == (235, max(30, color[1] // 3), max(45, color[2] // 3))
        assert (ghost_color >> 24) & 255 == 255
    assert len(observed['pixel']) == 4 and observed['pixel'][3] == 255
    assert all(abs(value - expected) <= 2 for value, expected in zip(observed['pixel'][:3], pixel))
    stamps.append(observed['submission'])
    epochs.append(observed['palette']['sceneEpoch'])
assert len(set(stamps[:5])) == 1 and stamps[0] > 0
assert stamps[5] > stamps[4] and stamps[6] > stamps[5]
assert stamps[6] == stamps[7] == stamps[8] < stamps[9] == stamps[10]
assert epochs[:6] == [0] * 6 and epochs[9:] == [0, 0]
assert epochs[6] == epochs[7] == epochs[8] > 0


def set_color(project, uuid, rgb):
    next(t for t in project['voxelTypes'] if t['uuid'] == uuid)['color'] = dict(zip(('r', 'g', 'b'), rgb))


expected = deepcopy(source)
expected['voxelTypes'].reverse()
set_color(expected, stone, purple)
set_color(expected, other, orange)
assert fixed == expected
expected['voxelTypes'].reverse()
set_color(expected, stone, violet)
assert streamed == expected
expected['voxelTypes'].reverse()
set_color(expected, stone, cyan)
clear = ghost['worldGenerator']['layers'][0]['operations'][-1]
assert clear['id'] == 'clear' and clear['interpretation'] == 'DENSITY'
assert clear['noiseGeneratorUuid'] == source['noise3D'][0]['uuid'] and clear['overrides'] == {'action': 'CLEAR'}
expected['worldGenerator']['layers'][0]['operations'].append(clear)
assert ghost == expected
assert read('preferences-before.json') == read('preferences.json')
print('World source UUID order, exact normal/ghost colors, native pixels, no-regeneration receipts and complete project/preferences replay PASS')
