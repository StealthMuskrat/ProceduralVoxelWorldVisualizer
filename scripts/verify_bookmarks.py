"""Check coordinate-only persistence and native workflow facts independently of the Java model."""
import argparse
import json
from pathlib import Path
import jsonschema

parser = argparse.ArgumentParser()
parser.add_argument('project', type=Path)
parser.add_argument('report', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
project = json.loads(args.project.read_text(encoding='utf-8'))
report = json.loads(args.report.read_text(encoding='utf-8'))
registry = json.loads((root / 'registries/bookmark-navigation.json').read_text())
assert registry['registryVersion'] == 1 and registry['projectFormatVersion'] == 2
assert registry['collections'] == ['noise2D', 'noise3D', 'world'] and registry['coordinatesOnly']
assert registry['duplicateNamesAllowed'] and registry['navigationAffectsProject'] is False
schema = json.loads((root / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
assert report['formatVersion'] == 1 and report['catalog'] == 2000
assert 0 < report['submittedUnfiltered'] <= 30
assert report['filtered'] == 1 and report['duplicateRows'] == 2
assert report['navigationCalls'] == 3 and report['owner'] == 'noise3D' and report['field'] == .625
bookmarks = project['bookmarks']
assert set(bookmarks) == {'noise2D', 'noise3D', 'world'}
assert [len(bookmarks[k]) for k in ('noise2D', 'noise3D', 'world')] == [2001, 1, 2]
identities = set()
for kind, entries in bookmarks.items():
    for entry in entries:
        assert set(entry) <= {'uuid', 'name', 'x', 'y', 'z'}
        assert entry['uuid'] not in identities
        identities.add(entry['uuid'])
        assert (entry.get('y') is not None) == (kind != 'noise2D')
        assert all(type(entry[k]) is int and -(1 << 31) <= entry[k] < (1 << 31)
                   for k in (('x', 'z') if kind == 'noise2D' else ('x', 'y', 'z')))
picked = bookmarks['noise2D'][-1]
assert [picked['name'], picked['x'], picked['z']] == ['picked-point', -101, -103]
renamed = bookmarks['world'][-1]
assert [renamed['name'], renamed['x'], renamed['y'], renamed['z']] == ['renamed', -31, -37, -41]
assert bookmarks['world'][0]['name'] == bookmarks['noise3D'][0]['name'] == 'duplicate'
assert project['worldGenerator']['layers'] == []
print('Bookmark schemas, clipped native rows, tab ownership, coordinate-only roundtrip and independent coordinate expectations PASS')
