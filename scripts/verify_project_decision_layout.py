"""Deferred independent project decision geometry and committed-only file replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])
report = json.loads((out / 'project-decision-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('cancelBeforeMutation', 'samePhaseReopenScoped', 'heldHistoryScoped',
            'failedSaveRetained', 'pathFocusRecovery', 'committedOnlySave',
            'knownPathGuardCancelled', 'staleSaveBlocked', 'failedOpenRetained', 'openRetryHandoff'):
    assert report[key] is True, key
assert report['replacements'] == 1 and report['exits'] == 0
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('DRAFT', 'GUARD', 'SAVE', 'OPEN', 'UNDO', 'REDO')
assert len(report['cases']) == 36
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, form) for i in range(6) for form in forms}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
versions = {'file': 0, 'history': 0}
path_ids = set()


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i, form = case['scenario'], case['form']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['work'], case['modal']); contains(case['modal'], case['body'])
    history = form in ('UNDO', 'REDO')
    keys = ('DISCARD', 'CANCEL') if history else ('primary', 'secondary', 'cancel') if form in ('DRAFT', 'GUARD') else ('primary', 'cancel')
    assert set(case['actions']) == set(keys)
    boxes = case['actions']
    for key in keys:
        contains(case['modal'], boxes[key])
        assert boxes[key]['y'] >= case['body']['y'] + case['body']['height'] - .1
    for left, right in zip(keys, keys[1:]):
        assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
        assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
    group = 'history' if history else 'file'
    assert case['request'] > versions[group]
    versions[group] = case['request']
    if form in ('SAVE', 'OPEN'):
        assert case['pathId'] != 0 and case['pathId'] not in path_ids
        path_ids.add(case['pathId'])
    else:
        assert case['pathId'] == 0
saved = json.loads((out / 'saved.pvproj.json').read_text(encoding='utf-8'))
opened = json.loads((out / 'opened-project.json').read_text(encoding='utf-8'))
incoming = json.loads((out / 'incoming.json').read_text(encoding='utf-8'))
for project, id_, value in ((saved, 'field', .125), (opened, 'incoming', .75)):
    assert project['formatVersion'] == 2 and 'packageVersion' not in project
    assert len(project['noise2D']) == 1
    noise = project['noise2D'][0]
    assert noise['id'] == id_ and noise['seedOffset'] == 0
    assert noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': value}
    assert not project['worldGenerator']['layers'] and not project['noise3D']
assert saved['project']['seed'] == 6
assert opened == incoming and opened['project']['uuid'] != saved['project']['uuid']
assert (out / 'cannot-replace-directory' / 'sentinel').read_text(encoding='utf-8') == 'retained'
for filename in ('cancelled-save.json', 'held-predecessor.json', 'stale-save.json'):
    assert not (out / filename).exists()
print('Project decision persistent geometry, request scopes and committed-only Save/Open replay PASS')
