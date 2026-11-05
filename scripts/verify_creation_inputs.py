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


background = out / 'background'
receipt = json.loads((background / 'profile-apply-native.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['apply', 'cancel', 'escape-ready', 'revert-ready', 'edit', 'active', 'hidden', 'close', 'retry', 'ready-cancel', 'ready-failure-cancel']
assert [case['mode'] for case in receipt['cases']] == modes
for case in receipt['cases']:
    mode = case['mode']
    applied = mode in ('apply', 'retry')
    assert case['applied'] is applied
    assert case['readyRetired'] is mode.startswith('ready-')
    assert case['failureHeld'] is (mode == 'ready-failure-cancel')
    assert case['queuedFrames'] == case['heldFrames'] == 12
    for key in ('projectPreserved', 'privatePreserved', 'preferencesPreserved', 'temporaryCleaned'):
        assert case[key] is True, (mode, key)
    profile = json.loads((background / mode / 'app-config.json').read_text(encoding='utf-8'))
    jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(profile)
    assert profile['creationDefaults']['noise2D.algorithm.CONSTANT.value'] == (.75 if applied else .25)
    created = json.loads((background / mode / 'created.json').read_text(encoding='utf-8'))
    assert [n['id'] for n in created['noise2D']] == ['existing', 'future']
    assert [n['algorithm']['parameters']['value'] for n in created['noise2D']] == [.125, .75 if applied else .25]
print('BACKGROUND_PROFILE_INDEPENDENT_PASS receipts / eleven saved profiles / authored and future defaults')
