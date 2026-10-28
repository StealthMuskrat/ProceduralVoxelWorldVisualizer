"""Deferred independent saved bookmark shape and native coordinate-input report."""
from pathlib import Path
import json
import sys
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'bookmark-input-project.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
bookmark, = project['bookmarks']['noise2D']
assert bookmark['name'] == 'exact' and bookmark['x'] == 17 and bookmark.get('y') is None and bookmark['z'] == -7
assert not project['bookmarks']['noise3D'] and not project['bookmarks']['world']
assert not project['noise2D'] and not project['noise3D'] and not project['worldGenerator']['layers']
report = json.loads((out / 'bookmark-input-native.json').read_text())
assert report['formatVersion'] == 1 and report['navigationCount'] == 2
for key in ['invalidTeleportRetained', 'overflowBookmarkRetained', 'tabOwned', 'exactEndpoints', 'callbackRetained', 'oneUndoRedo']:
    assert report[key] is True
for key in ['displayedOverflowBlocked', 'sameUuidReopenRejected', 'renameSourcePreserved',
            'boundedRetryDetails', 'runtimeDetailsCleared', 'privateInputsPreserved',
            'externalCancelClosed', 'navigationErrorLogged', 'pickerFailureRetained']:
    assert report[key] is True, key
print('BOOKMARK_INPUT_INDEPENDENT_PASS exact 2D bookmark / absent invalid input / schema / native report')
