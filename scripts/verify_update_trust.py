"""Deferred independent replay of private manual-update trust and configuration persistence."""
import json
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit
import jsonschema

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'update-trust-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['networkRequests'] == 0
for field in ('invalidBlocked', 'cachedFeedback', 'hiddenDraftRetained', 'staleBaseRevert',
              'failedWriteRetry', 'canonicalAfterSave', 'profileWriterRoundTrip', 'projectPreserved'):
    assert report[field] is True, field
subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                str(out / 'user-config.json')], check=True)
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
assert 'files' not in saved
assert saved['appearance']['theme'] == 'Light' and saved['workspace']['width'] == 1234
assert saved['input']['shortcuts']['Profiler'] == 'Alt+F9'
performance = saved['performance']
assert performance['updateEndpoint'] == 'https://updates.example.test/meta.json'
assert performance['trustedOrigins'] == ['https://updates.example.test', 'https://releases.example.test']
for origin in performance['trustedOrigins']:
    parsed = urlsplit(origin)
    assert parsed.scheme == 'https' and parsed.hostname and not parsed.username and not parsed.password
    assert not parsed.path and not parsed.query and not parsed.fragment
endpoint = urlsplit(performance['updateEndpoint'])
assert endpoint.scheme + '://' + endpoint.netloc in performance['trustedOrigins']
periodic = json.loads((out / 'periodic-preferences.json').read_text(encoding='utf-8'))
assert periodic['performance']['updateEndpoint'] == '' and periodic['performance']['trustedOrigins'] == []
profile = json.loads((out / 'app-config.json').read_text(encoding='utf-8'))
schema = json.loads((root / 'schema/app-config.schema.json').read_text(encoding='utf-8'))
jsonschema.Draft202012Validator(schema).validate(profile)
assert profile['controls']['noise']['label'] == 'Preference writer fixture'
metadata = json.loads((out / 'release-metadata.json').read_text(encoding='utf-8'))
assert metadata == {'formatVersion': 1, 'version': '1.2.3', 'releaseUrl': 'https://releases.example.test/1.2.3'}
assert all(p.stat().st_size <= 8 * 1024 * 1024 for p in out.glob('*.json'))
assert not list(out.rglob('.user-config-*.tmp')) and not list(out.rglob('.app-config-*.tmp'))
print('Update trust privacy, canonical persistence, profile schema and manual metadata replay PASS')
