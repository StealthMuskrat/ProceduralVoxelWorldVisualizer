"""Deferred independent file publication, successor routes, native geometry and persisted values."""
from copy import deepcopy
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('project-file-completion-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
assert report['physicalDpiVerified'] is False
assert report['callbacksAfterPopup'] == 14 and report['sourceBytesPreserved'] is True
expected = [('draft-cancel', 0, 'source', 0, 0, 0, True, 0),
            ('guard-cancel', 0, 'dirty', 0, 0, 0, True, 0),
            ('open-cancel', 0, 'source', 0, 0, 0, True, 0),
            ('save-cancel', 0, 'source', 0, 0, 0, True, 0),
            ('open-refresh', 1, 'candidate', 1, 0, 1, False, 1),
            ('new-successor', 1, 'new', 1, 0, 0, False, 2),
            ('exit-failure', 0, 'source', 0, 1, 0, True, 3),
            ('gate-successor', 0, 'source', 0, 0, 0, True, 3),
            ('save-busy', 0, 'source', 0, 0, 1, True, 3),
            ('escape', 0, 'source', 0, 0, 0, True, 3),
            ('held', 0, 'source', 0, 0, 0, True, 3)]
assert len(report['routes']) == len(expected)
for row, (phase, revision, canonical, replacements, exits, validations, private, errors) in zip(report['routes'], expected):
    assert row == dict(phase=phase, revisionDelta=revision, canonical=canonical,
                       replacements=replacements, exits=exits, validations=validations,
                       privateRetained=private, errorCount=errors)
errors = report['errors']
assert [row['code'] for row in errors] == ['PROJECT_REPLACEMENT_REFRESH_FAILED', 'PROJECT_REPLACEMENT_REFRESH_FAILED', 'PROJECT_EXIT_CALLBACK_FAILED']
assert [row['message'] for row in errors] == ['Project opened; UI refresh failed: open refresh boundary',
        'New project created; UI refresh failed: new refresh boundary',
        'Exit request callback failed: exit boundary']
assert all(row['count'] == 1 and row['severity'] == 'ERROR' and row['subsystem'] == 'PROJECT'
           and row['actionRequired'] is True for row in errors)
cases = report['cases']
assert [(row['phase'], row['scenario']) for row in cases] == [(phase, i) for phase in ('DRAFT', 'GUARD', 'OPEN', 'SAVE') for i in range(6)]
ids = {}
for row in cases:
    phase, i = row['phase'], row['scenario']
    assert row['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert row['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    keys = {'primary', 'secondary', 'cancel'} if phase in ('DRAFT', 'GUARD') else {'primary', 'cancel'}
    assert set(row['actions']) == set(row['ids']) == keys
    assert all(value != 0 for value in row['ids'].values())
    ids.setdefault(phase, row['ids'])
    assert ids[phase] == row['ids']
    for box in [row['body'], *row['actions'].values()]:
        assert all(math.isfinite(value) for value in box.values())
        assert box['width'] > 0 and box['height'] > 0
        for axis, size in (('x', 'width'), ('y', 'height')):
            assert box[axis] >= row['window'][axis] - 1.1
            assert box[axis] + box[size] <= row['window'][axis] + row['window'][size] + 1.1
source = read('source-project.json')
candidate = deepcopy(source)
candidate['noise2D'][0]['algorithm']['parameters']['value'] = 4
dirty = deepcopy(source)
dirty['noise2D'][0]['id'] = 'dirty-field'
assert read('candidate-project.json') == read('opened-project.json') == candidate
assert read('dirty-project.json') == dirty
assert read('saved-project.json') == read('after.json') == source
new = read('new-project.json')
assert new['project']['uuid'] != source['project']['uuid']
assert all(not new[key] for key in ('noise2D', 'noise3D', 'voxelTypes', 'voxelGroups', 'voxelSets', 'folders'))
assert not new['worldGenerator']['layers'] and all(not values for values in new['bookmarks'].values())
for phase in ('opened-project', 'new-project'):
    trace = read(phase + '-trace.json')
    assert trace['position'] == dict(x=2, y=0, z=3)
    if phase == 'new-project':
        assert trace['finalType'] == 0 and not trace['steps']
    else:
        assert trace['finalType'] == 1
        assert [step['action'] for step in trace['steps']] == ['SET', 'NO_CHANGE']
        assert all(step['field'] == (4 + 2 * (2 + 1) + 3) * 2 == 26 for step in trace['steps'])
assert read('preferences-before.json') == read('preferences.json')
assert not (out / 'escape-must-not-write.json').exists()
assert not (out / 'held-must-not-write.json').exists()
print('File post-popup completion, eleven routes, exact publications, successor ownership, native paths, committed-only Save and immutable source/preferences PASS')

# Deferred production background Open sub-replay, invoked by the same native command.
background_root = out / 'background-open'
background = json.loads((background_root / 'project-open-native.json').read_text(encoding='utf-8'))
assert background['formatVersion'] == 1
background_cases = {row['mode']: row for row in background['cases']}
assert len(background_cases) == len(background['cases']) == 8
assert set(background_cases) == {'SUCCESS', 'CANCEL', 'ESCAPE', 'COMMITTED', 'PRIVATE',
                                 'COMPILER', 'MALFORMED', 'POLICY'}
for mode, row in background_cases.items():
    assert row['workerOffUi'] and row['preferencesPreserved']
    assert row['responsiveFrames'] == (0 if mode == 'MALFORMED' else 12)
    assert row['replacements'] == (1 if mode == 'SUCCESS' else 0)
    assert row['seed'] == (99 if mode == 'SUCCESS' else 42 if mode == 'COMMITTED' else 41)
    assert row['phase'] == ('OPEN' if mode in ('COMPILER', 'MALFORMED', 'POLICY') else 'IDLE')
    project = json.loads((background_root / f'{mode}-after.json').read_text(encoding='utf-8'))
    assert project['project']['seed'] == row['seed']
    assert len(project['noise2D']) == 1 and not project['noise3D']
    noise = project['noise2D'][0]
    assert noise['id'] == ('incoming' if mode == 'SUCCESS' else 'owned')
    assert noise['algorithm']['key'] == 'CONSTANT'
    assert noise['algorithm']['parameters'] == {'value': .75 if mode == 'SUCCESS' else .25}
    assert not project['worldGenerator']['layers']
