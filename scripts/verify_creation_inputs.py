"""Deferred independent profile-input report, schema and exact persisted-value checks."""
import json
import math
import sys
from pathlib import Path
import jsonschema

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'creation-input-native.json').read_text(encoding='utf-8'))
profile = json.loads((out / 'app-config.json').read_text(encoding='utf-8'))
schema = json.loads((root / 'schema/app-config.schema.json').read_text(encoding='utf-8'))
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(profile)
assert report['formatVersion'] == 2
for key in ('rawNavigation', 'hiddenErrorBlocked', 'vectorAtomic', 'writeRetry',
            'projectPreserved', 'privateSeedPreserved', 'cachedRows'):
    assert report[key] is True, key
assert 0 < report['submittedRows'] < report['totalRows']
assert report['rowHeight'] > 0 and report['rowBounds']['height'] > 0
assert report['submittedRows'] <= math.ceil(report['rowBounds']['height'] / report['rowHeight']) + 2
values = profile['creationDefaults']
assert type(values['noise2D.seedOffset']) is int
assert values['noise2D.seedOffset'] == report['exactSeed'] == 2**63 - 1
assert values['noise2D.algorithm.CONSTANT.value'] == report['tiny'] == 1e-9
assert values['processing.DOT.input.a'] == report['vector'] == [3.5, 2.25, 9]
assert set(values) == {'noise2D.seedOffset', 'noise2D.algorithm.CONSTANT.value', 'processing.DOT.input.a'}
print('CREATION_INPUT_INDEPENDENT_PASS exact seed / small double / complete vector / private recovery report')
