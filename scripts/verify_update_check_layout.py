"""Deferred independent update-window geometry, activation ownership and unchanged-file replay."""
from pathlib import Path
import json
import math
import sys
from urllib.parse import urlsplit

out = Path(sys.argv[1])
report = json.loads((out / 'update-check-layout-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['fontSource'] == 'user-scale-fixture'
for key in ('escapePreserved', 'runningEscapeCancelled', 'heldSuccessorsBlocked',
            'collapsedContextsReleased', 'closedContextsReleased', 'browserRetryRetained',
            'callbackFeedbackScoped', 'projectPreserved'):
    assert report[key] is True, key
assert report['fetchCalls'] == 12 and report['fetchErrors'] == 1
assert report['linkErrors'] == 2 and report['reentrantCalls'] == 2 and report['configured'] == 1
assert report['networkRequests'] == report['externalBrowserLaunches'] == 0
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
phases = ('IDLE', 'RUNNING', 'READY', 'FAILED', 'CANCELLED', 'STALE', 'CLOSED')
assert len(report['cases']) == 42
assert {(c['phase'], c['scenario']) for c in report['cases']} == {(p, i) for p in phases for i in range(6)}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
identities = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - .1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + .1


for case in report['cases']:
    i = case['scenario']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['window'], case['body'])
    boxes = case['actions']
    assert set(boxes) == set(case['ids']) == {'check', 'cancel', 'configure', 'open', 'copy'}
    assert len(set(case['ids'].values())) == 5 and all(case['ids'].values())
    previous_bottom = case['body']['y'] + case['body']['height']
    for row in (('check', 'cancel', 'configure'), ('open', 'copy')):
        for key in row:
            contains(case['window'], boxes[key])
            assert boxes[key]['y'] >= previous_bottom - .1
        for left, right in zip(row, row[1:]):
            assert abs(boxes[left]['y'] - boxes[right]['y']) <= .1
            assert boxes[left]['x'] + boxes[left]['width'] <= boxes[right]['x'] + .1
        previous_bottom = max(boxes[key]['y'] + boxes[key]['height'] for key in row)
    assert case['request'] > 0
    identity = (case['request'], case['ids'])
    phase = case['phase']
    if phase not in identities:
        identities[phase] = identity
    assert identities[phase] == identity
assert len({value[0] for value in identities.values()}) == 7
metadata = json.loads((out / 'reviewed-metadata.json').read_text(encoding='utf-8'))
assert metadata == {'formatVersion': 1, 'version': '0.2.0',
                    'releaseUrl': 'https://releases.example.test/' + 'segment/' * 300}
url = urlsplit(metadata['releaseUrl'])
assert url.scheme == 'https' and url.hostname == 'releases.example.test'
assert not url.username and not url.password and not url.fragment
assert len(metadata['releaseUrl']) < 4096
assert report['openedTargets'] == report['copiedTargets'] == [metadata['releaseUrl']]
preferences = json.loads((out / 'preferences.json').read_text(encoding='utf-8'))
assert preferences == json.loads((out / 'preferences-before.json').read_text(encoding='utf-8'))
assert preferences['performance']['updateEndpoint'] == 'https://updates.example.test/meta.json'
assert preferences['performance']['trustedOrigins'] == ['https://updates.example.test', 'https://releases.example.test']
project = json.loads((out / 'unchanged-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and len(project['noise2D']) == 1
assert project['noise2D'][0]['id'] == 'existing' and project['noise2D'][0]['seedOffset'] == 0
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert not project['worldGenerator']['layers'] and not project['noise3D']
assert all(not values for values in project['bookmarks'].values())
print('Update result geometry, scoped activation, exact link routing and unchanged project replay PASS')
