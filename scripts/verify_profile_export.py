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
