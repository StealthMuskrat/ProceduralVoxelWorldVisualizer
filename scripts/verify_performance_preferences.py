"""Deferred independent replay of exact persisted budgets and private-draft publication."""
import json
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'performance-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for field in ('invalidCouplingBlocked', 'rawBudgetRetained', 'periodicSaveExcludedDraft',
              'cachedFeedback', 'hiddenDraftRetained', 'staleBaseRevert', 'failedWriteRetry',
              'exactLongRoundTrip', 'canonicalAfterSave', 'restartNotice', 'projectPreserved',
              'engineRetained', 'boundedNumericPairs'):
    assert report[field] is True, field
subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                str(out / 'user-config.json')], check=True)
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
limits = saved['performance']
expected = {'workers': 4, 'maxPreviewDimension': 16, 'previewCellBudget': 9007199254740993,
            'cacheMiB': 0, 'engineMiB': 256, 'sceneCpuMiB': 16, 'sceneGpuMiB': 8,
            'uploadKiB': 64, 'renderDistance': 0, 'verticalDistance': 0, 'lodStart': 1,
            'lodQuality': 2, 'streamChunks': 27}
for key, value in expected.items():
    assert type(limits[key]) is int and limits[key] == value, (key, limits[key])
assert saved['visualization']['previewSize'] == 16 <= limits['maxPreviewDimension']
assert saved['visualization']['grid'] is False
assert limits['updateEndpoint'] == 'https://updates.example.test/meta.json'
assert limits['trustedOrigins'] == ['https://updates.example.test']
assert saved['appearance']['theme'] == 'Light' and saved['workspace']['width'] == 1234
assert saved['input']['shortcuts']['Profiler'] == 'Alt+F9'
periodic = json.loads((out / 'periodic-preferences.json').read_text(encoding='utf-8'))
assert periodic['visualization']['previewSize'] == 32
assert periodic['performance']['maxPreviewDimension'] == 96
assert periodic['performance']['previewCellBudget'] == 96 ** 3
assert periodic['performance']['workers'] == 2 and periodic['performance']['cacheMiB'] == 128
assert periodic['performance']['updateEndpoint'] == ''
assert not list(out.rglob('.user-config-*.tmp'))
assert 'files' not in saved
print('Performance exact integers, complete limits, privacy, retry and unrelated-preference replay PASS')


background = out / 'background'
receipt = json.loads((background / 'preferences-background-native.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['limits-apply', 'limits-cancel', 'limits-retry', 'scales-apply', 'scales-escape', 'scales-edit', 'trust-apply', 'trust-revert', 'trust-hidden']
assert [case['mode'] for case in receipt['cases']] == modes
for case in receipt['cases']:
    mode = case['mode']
    applied = mode.endswith('apply') or mode == 'limits-retry'
    assert case['applied'] is applied
    assert case['queuedFrames'] == case['heldFrames'] == 12
    for key in ('projectRetained', 'privateRetained', 'engineRetained', 'temporaryCleaned'):
        assert case[key] is True, (mode, key)
    base = json.loads((background / mode / 'user-before.json').read_text(encoding='utf-8'))
    current = json.loads((background / mode / 'user-config.json').read_text(encoding='utf-8'))
    if applied:
        if mode.startswith('limits'):
            base['performance']['cacheMiB'] = 256
        elif mode.startswith('scales'):
            base['appearance']['globalScale'] = 1.25
            base['appearance']['windowScales']['Resources'] = 1.5
        else:
            base['performance']['updateEndpoint'] = 'https://updates.example.invalid/latest.json'
            base['performance']['trustedOrigins'] = ['https://updates.example.invalid']
    assert current == base, mode
    original = json.loads((background / mode / 'before.json').read_text(encoding='utf-8'))
    after = json.loads((background / mode / 'after.json').read_text(encoding='utf-8'))
    assert original == after and len(after['noise2D']) == 1
    assert after['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
    subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                    str(background / mode / 'user-config.json')], check=True)
print('BACKGROUND_PREFERENCE_INDEPENDENT_PASS nine exact preference patches / eighteen unchanged projects')


appearance_input = background / 'appearance-input'
receipt = json.loads((appearance_input / 'appearance-input-background-native.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['theme-apply', 'theme-cancel', 'theme-raw', 'theme-owner', 'theme-reopen', 'theme-retry',
         'bindings-apply', 'bindings-escape', 'bindings-record', 'bindings-revert', 'bindings-hidden', 'bindings-retry']
assert [case['mode'] for case in receipt['cases']] == modes
for case in receipt['cases']:
    mode = case['mode']
    applied = mode.endswith('apply') or mode.endswith('retry')
    assert case['applied'] is applied
    assert case['queuedFrames'] == case['heldFrames'] == 12
    for key in ('projectRetained', 'privateRetained', 'engineRetained', 'temporaryCleaned'):
        assert case[key] is True, (mode, key)
    folder = appearance_input / mode
    base = json.loads((folder / 'user-before.json').read_text(encoding='utf-8'))
    current = json.loads((folder / 'user-config.json').read_text(encoding='utf-8'))
    if applied:
        if mode.startswith('theme'):
            # ImGui Text is the public palette key 0; fixed exact binary fractions are independent expectations.
            base['appearance']['theme'] = 'Light'
            base['appearance']['colors'] = {'0': [.125, .25, .5, 1]}
            base['appearance']['statusColors'] = {'Warning': [.5, .25, .125, .75]}
        else:
            base['input']['shortcuts']['Profiler'] = 'Alt+F9'
    assert current == base, mode
    original = json.loads((folder / 'before.json').read_text(encoding='utf-8'))
    after = json.loads((folder / 'after.json').read_text(encoding='utf-8'))
    assert original == after and len(after['noise2D']) == 1
    assert after['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
    assert not list(folder.glob('*.tmp')) and not list(folder.glob('.*.tmp'))
    subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                    str(folder / 'user-config.json')], check=True)
print('BACKGROUND_APPEARANCE_INPUT_INDEPENDENT_PASS twelve exact preference patches / twenty-four unchanged projects')
