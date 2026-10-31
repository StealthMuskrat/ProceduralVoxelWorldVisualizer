"""Deferred independent Resume geometry, pause episodes, routes, policy and isolation replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('preview-resume-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'pauseEpisodesScoped', 'sourceLimitsScoped', 'hiddenTabScoped',
            'postDrawClockRechecked', 'clockRetryRetained', 'acceptedFailureConsumed',
            'successorPausePreserved', 'callbacksBalanced', 'privatePreserved'):
    assert report[key] is True, key
assert report['physicalDpiVerified'] is False and report['gpuRecoveryVerified'] is False
assert len(report['cases']) == 18
assert {(c['slot'], c['scenario']) for c in report['cases']} == {(slot, i) for slot in range(3) for i in range(6)}
identities = {}
for case in report['cases']:
    slot, i = case['slot'], case['scenario']
    assert case['kind'] == ('VOLUME' if slot == 0 else 'STREAMING')
    assert case['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert case['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    outer, inner = case['window'], case['action']
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1
    assert case['id'] != 0
    if slot not in identities:
        identities[slot] = case['id']
    assert identities[slot] == case['id']
assert len(set(identities.values())) == 3
deliveries = report['deliveries']
assert [entry['slot'] for entry in deliveries] == [0, 0, 0, 0, 1, 2]
versions = {}
for entry in deliveries:
    slot = entry['slot']
    assert entry['kind'] == ('VOLUME' if slot == 0 else 'STREAMING')
    assert entry['level'] == 3 and entry['pauseVersion'] > 0
    assert entry['sourceKey'] == ('volume-source', 'surface-source', 'world-source')[slot]
    assert entry['pauseVersion'] > versions.get(slot, 0)
    versions[slot] = entry['pauseVersion']
assert len(report['recovery']) == 3
for slot, entry in enumerate(report['recovery']):
    assert entry == dict(slot=slot, levels=[5, 3, 2, 1, 0], pauseVersion=versions[slot])
events = read('resume-events.json')
assert events['counts'] == dict(retained=3, unread=3, actionRequired=3, evicted=0)
expected = {('PREVIEW_RESUME_FAILED', 'resume clock boundary'),
            ('PREVIEW_RESUME_REFRESH_FAILED', 'resume refresh boundary'),
            ('PREVIEW_RESUME_REFRESH_FAILED', 'resume successor boundary')}
assert {(e['code'], e['message']) for e in events['entries']} == expected
for entry in events['entries']:
    assert entry['count'] == 1 and entry['severity'] == 'ERROR' and entry['subsystem'] == 'RENDERING'
    assert entry['actionRequired'] and not entry['read'] and not entry['dismissed'] and entry['target'] is None
assert read('resume-project.json') == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
print('Preview Resume geometry, exact pause/source routes, consumed failure, gradual restoration and isolation PASS')
