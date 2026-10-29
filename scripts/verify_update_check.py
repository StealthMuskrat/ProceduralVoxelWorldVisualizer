"""Deferred independent replay of manual check result/link routing and release metadata."""
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
import jsonschema

root = Path(__file__).resolve().parent.parent
out = Path(sys.argv[1])
report = json.loads((out / 'update-check-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for field in ('requestDeduplicated', 'queuedCancellation', 'stalePublicationDiscarded',
              'verifiedLinkRouting', 'failedOpenRetry', 'closedRequestCancelled',
              'currentVersionCompared', 'collapsedCompletion', 'projectPreserved'):
    assert report[field] is True, field
assert report['networkRequests'] == 0 and report['externalBrowserLaunches'] == 0
assert report['fetchCalls'] == 3 and report['installed'] == '0.1.0'
assert report['openedTargets'] == report['copiedTargets'] == ['https://releases.example.test/0.2.0']
schema = json.loads((root / 'schema/release-metadata.schema.json').read_text(encoding='utf-8'))
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
captures = []
for name in ('metadata-newer.json', 'metadata-current.json'):
    value = json.loads((out / name).read_text(encoding='utf-8'))
    validator.validate(value)
    uri = urlsplit(value['releaseUrl'])
    assert uri.scheme == 'https' and uri.hostname == 'releases.example.test'
    assert not uri.username and not uri.password and not uri.fragment
    captures.append(value)
assert captures[0]['version'] == '0.2.0' and captures[1]['version'] == '0.1.0+build.7'
core = lambda version: tuple(map(int, version.split('+')[0].split('.')))
assert core(captures[0]['version']) > core(report['installed'])
assert core(captures[1]['version']) == core(report['installed'])
invalid = json.loads((root / 'fixtures/invalid-release-version.json').read_text(encoding='utf-8'))
assert list(validator.iter_errors(invalid))
source = (root / 'editor-ui/src/main/java/io/voxelcreator/editor/Desktop.java').read_text(encoding='utf-8')
assert re.search(r'APPLICATION_VERSION\s*=\s*"0\.1\.0"', source)
print('Manual check publication, verified link routing, version comparison and metadata schema replay PASS')
