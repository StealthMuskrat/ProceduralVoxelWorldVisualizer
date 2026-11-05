"""Deferred independent replay for reviewed production-panel profiler exports."""
import json
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'profile-export-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for field in ('protectedProject', 'reviewSurvivedReplacementAndClear', 'failedWriteRetry',
              'acceptedConsumed', 'explicitPrepareReplaced', 'collapsedAndClosed',
              'cachedRows', 'projectPreserved', 'actualJsonAndCsvButtons'):
    assert report[field] is True, field
captures = []
for name, value, kind, label in (
        ('reviewed', 123, 'QUEUE_DEPTH', 'reviewed,雪'),
        ('replacement', 999, 'CACHE_BYTES', 'csv replacement')):
    subprocess.run([sys.executable, str(root / 'scripts/verify_profile_capture.py'),
                    str(out / (name + '.json')), str(out / (name + '.csv'))], check=True)
    capture = json.loads((out / (name + '.json')).read_text(encoding='utf-8'))
    assert capture['state'] == 'STOPPED'
    assert len(capture['samples']) == 1 and len(capture['series']) == 1
    sample = capture['samples'][0]
    assert (sample['value'], sample['kind'], sample['label']) == (value, kind, label)
    captures.append(capture)
assert captures[0]['captureId'] != captures[1]['captureId']
project = json.loads((out / 'protected-project.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and project['noise2D'][0]['id'] == 'field'
assert project['noise2D'][0]['algorithm']['parameters']['value'] == .125
assert all(c['samples'][0]['projectUuid'] == project['project']['uuid'] for c in captures)
assert not list(out.rglob('.profile-*.tmp'))
print('Profile export review, retry, protected project and JSON/CSV replay PASS')

# Deferred worker staging/readiness and exact current-control cancellation.
background = out / 'background'
receipt = json.loads((background / 'profile-export-background-native.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['json', 'csv', 'ready-cancel', 'ready-failure-cancel', 'ready-escape', 'ready-close', 'ready-collapse', 'ready-path']
assert [row['mode'] for row in receipt['cases']] == modes
for row in receipt['cases']:
    mode = row['mode']
    published = mode in ('json', 'csv')
    assert row['published'] is published and row['readyRetired'] is (not published)
    assert row['failureHeld'] is (mode == 'ready-failure-cancel')
    assert row['queuedFrames'] == row['heldFrames'] == 12
    assert all(row[key] is True for key in ('projectPreserved', 'privatePreserved', 'preferencesPreserved', 'temporaryCleaned'))
    folder = background / mode
    before = json.loads((folder / 'before.json').read_text(encoding='utf-8'))
    after = json.loads((folder / 'after.json').read_text(encoding='utf-8'))
    assert before == after and after['noise2D'][0]['id'] == 'field'
    assert after['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
    if published:
        subprocess.run([sys.executable, str(root / 'scripts/verify_profile_capture.py'),
                        str(folder / 'capture.json'), str(folder / 'capture.csv')], check=True)
        capture = json.loads((folder / 'capture.json').read_text(encoding='utf-8'))
        assert capture['state'] == 'STOPPED' and len(capture['samples']) == len(capture['series']) == 1
        assert capture['samples'][0]['value'] == 123 and capture['samples'][0]['label'] == 'reviewed,雪'
        assert capture['samples'][0]['projectUuid'] == after['project']['uuid']
    else:
        assert (folder / 'capture.json').read_text(encoding='utf-8') == 'previous capture'
assert not list(background.rglob('.profile-*.tmp'))
print('BACKGROUND_PROFILE_EXPORT_INDEPENDENT_PASS eight decisions / canonical JSON+CSV / sixteen unchanged projects')
