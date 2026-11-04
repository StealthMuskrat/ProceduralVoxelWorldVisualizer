"""Deferred package modal geometry and independent portable payload replay."""
from pathlib import Path
import json
import math
import sys
import jsonschema

out = Path(sys.argv[1])
report = json.loads((out / 'package-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('exactIncludedReview', 'cachedQueriesAndClosure', 'pendingRetargetBlocked',
            'externalResetBlocked', 'activeProjectProtected', 'failedStageRetained', 'successfulRetryConsumed',
            'cancelBeforeIo', 'reloadReviewRequired', 'explicitMissingRemoval',
            'foreignReloadBlocked', 'malformedImportRetained', 'importHandoffOnly', 'privateStatePreserved'):
    assert report[key] is True, key
assert report['stageAttempts'] == 2
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
forms = ('Export-Choices', 'Export-Included', 'Export-Path', 'Import-Path')
assert len(report['cases']) == 24
assert {(c['scenario'], c['form']) for c in report['cases']} == {(i, f) for i in range(6) for f in forms}
globals_, windows = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
row_ids = set()


def contains(outer, inner):
    assert all(math.isfinite(v) for v in inner.values())
    assert inner['width'] > 0 and inner['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i, form = case['scenario'], case['form']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == windows[i]
    contains(case['work'], case['modal']); contains(case['modal'], case['body'])
    rows = (('submit', 'cancel'),) if form == 'Import-Path' else (
        ('submit', 'cancel', 'reload', 'remove-unavailable'), ('choices', 'included', 'location'))
    actions = case['actions']
    assert set(actions) == {key for row in rows for key in row}
    last_bottom = case['body']['y'] + case['body']['height']
    for row in rows:
        for key in row:
            contains(case['modal'], actions[key])
            assert actions[key]['y'] >= last_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(actions[left]['y'] - actions[right]['y']) <= .1
            assert actions[left]['x'] + actions[left]['width'] <= actions[right]['x'] + .1
        last_bottom = max(actions[key]['y'] + actions[key]['height'] for key in row)
    if form in ('Export-Choices', 'Export-Included'):
        contains(case['body'], case['list'])
        line, frame, gap = (case['metrics'][k] for k in ('line', 'frame', 'gap'))
        assert case['stride'] == math.ceil(frame + gap)
        if form == 'Export-Choices':
            assert case['choices'] == 1 and case['included'] == 0 and len(case['ids']) == 1
            identity = tuple(sorted(case['ids'].items()))
            assert identity not in row_ids and all(value != 0 for value in case['ids'].values())
            row_ids.add(identity)
        else:
            assert case['choices'] == 0 and 0 < case['included'] <= 4 and case['ids'] == {}
            assert case['included'] <= math.ceil(case['list']['height'] / case['stride']) + 2
    else:
        assert case['list'] is None and case['choices'] == case['included'] == 0 and case['ids'] == {}
package = json.loads((out / 'selection.pvresources.json').read_text(encoding='utf-8'))
assert package['packageVersion'] == 1
payload = package['resources']
assert [t['id'] for t in payload['voxelTypes']] == ['type-00000', 'type-01999']
assert len(payload['voxelSets']) == 1 and payload['voxelSets'][0]['id'] == 'catalog'
assert payload['voxelSets'][0]['voxelTypeUuids'] == [t['uuid'] for t in payload['voxelTypes']]
assert len(payload['noise2D']) == 1 and payload['noise2D'][0]['id'] == 'shared-field'
assert payload['noise2D'][0]['algorithm']['parameters'] == {'value': .625}
assert not payload['noise3D'] and not payload['worldGenerator']['layers']
protected = json.loads((out / 'active-project.pvproj.json').read_text(encoding='utf-8'))
assert protected['formatVersion'] == 2 and 'packageVersion' not in protected
assert len(protected['voxelTypes']) == 2000 and len(protected['voxelSets']) == 1
assert protected['noise2D'][0]['id'] == 'shared-field'
assert not (out / 'cancelled.pvresources.json').exists()
assert not list(out.glob('*.tmp'))
print('Package modal persistent geometry, exact dependency payload and protected project replay PASS')

# Deferred background package staging, complete source models and exact final destinations.
background = out / 'background-export'
project_schema = json.loads((Path(__file__).resolve().parent.parent / 'schema/project.schema.json').read_text(encoding='utf-8'))
project_validator = jsonschema.Draft202012Validator(project_schema, format_checker=jsonschema.FormatChecker())
loading = json.loads((background / 'background-export.json').read_text())
assert loading['formatVersion'] == 1 and loading['physicalInputVerified'] is False
assert [c['mode'] for c in loading['cases']] == ['success', 'cancel', 'escape', 'raw', 'retry', 'close']
for case in loading['cases']:
    mode = case['mode']
    published = mode in ['success', 'retry']
    assert case['published'] is published and case['responsiveFrames'] == 12
    assert case['privateRetained'] is True and case['originalBytesRetained'] is True
    assert case['writes'] == (2 if mode == 'retry' else 1)
    assert case['replacements'] == (2 if mode == 'retry' else 1 if mode == 'success' else 0)
    assert case['deletions'] == (0 if mode == 'success' else 1)
    folder = background / mode
    original = json.loads((folder / 'source.json').read_text())
    retained = json.loads((folder / 'source-after.json').read_text())
    package = json.loads((folder / 'selection 资源.pvresources.json').read_text())
    prior = json.loads((folder / 'prior-package.pvresources.json').read_text())
    assert package['packageVersion'] == 1 and set(package) == {'packageVersion', 'resources'}
    for project in [original, retained, package['resources'], prior['resources']]: project_validator.validate(project)
    assert len(original['noise2D']) == len(retained['noise2D']) == 1
    expected = json.loads(json.dumps(original))
    if mode == 'raw': expected['project']['seed'] = 18
    assert expected == retained
    if published:
        assert package['resources']['noise2D'] == original['noise2D']
        assert package['resources']['noise2D'][0]['algorithm']['parameters']['value'] == .625
    else:
        assert package == prior
        assert (folder / 'selection 资源.pvresources.json').read_bytes() == (folder / 'prior-package.pvresources.json').read_bytes()
    assert not list(folder.glob('.voxel-package-*'))
replay = json.loads((background / 'engine-replay.json').read_text())
assert replay == {'formatVersion': 1, 'fields': 252, 'cells': 3528, 'roots': [2, 4]}
print('BACKGROUND_EXPORT_INDEPENDENT_PASS staged publication / preserved sources and prior packages / 252 fields / 3528 EMPTY cells')
