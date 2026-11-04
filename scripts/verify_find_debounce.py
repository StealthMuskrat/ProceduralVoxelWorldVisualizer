"""Deferred independent search receipts and whole-document/preferences isolation checks."""
import json
from pathlib import Path
import sys

out = Path(sys.argv[1])
def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))

r = read('find-debounce-native.json')
assert r['formatVersion'] == 1 and r['resources'] == 1100
assert r['initialQueries'] == r['typingQueries'] == 1
assert 0 < r['initialRows'] <= 40 and r['quietNanos'] == 150_000_000
for key in ('pendingNavigationBlocked', 'heldRowRetired', 'nativeTypingDebounced',
            'explicitImmediate', 'hiddenCancelled'):
    assert r[key] is True, key
assert r['rawInput'] == '-' and r['physicalDpiVerified'] is False
assert len(r['cases']) == 3
for i, case in enumerate(r['cases']):
    assert case['scenario'] == i and case['scale'] == (1, 2.5, 6.25)[i]
    assert 0 < case['submitted'] <= 40 and case['stride'] > 0
    for key in ('results', 'query'):
        rect = case[key]
        assert rect['width'] > 0 and rect['height'] > 0
before, after = read('before.json'), read('after.json')
assert before == after
assert len(after['noise2D']) == 1100
assert [n['id'] for n in after['noise2D']] == [f'field-{i}' for i in range(1099)] + ['needle']
for n in after['noise2D']:
    assert n['algorithm']['key'] == 'CONSTANT'
    assert n['algorithm']['parameters'] == {'value': .125}
assert read('preferences-before.json') == read('preferences.json')
print('Find debounce independent receipts, native geometry and complete project/preferences isolation PASS')


background = out / 'background'
receipt = json.loads((background / 'find-background-native.json').read_text(encoding='utf-8'))
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = ['ready', 'coalesced', 'retry', 'cancel', 'collapse', 'revision', 'live-query', 'cancelled-worker', 'close']
assert [case['mode'] for case in receipt['cases']] == modes
for case in receipt['cases']:
    mode = case['mode']
    assert case['queuedFrames'] == case['heldFrames'] == 12
    for key in ('privateRetained', 'preferencesRetained', 'navigationGuarded'):
        assert case[key] is True, (mode, key)
    original = json.loads((background / mode / 'before.json').read_text(encoding='utf-8'))
    current = json.loads((background / mode / 'after.json').read_text(encoding='utf-8'))
    assert len(original['noise2D']) == 256
    assert [n['id'] for n in original['noise2D']] == [f'field-{i}' for i in range(255)] + ['needle']
    for noise in original['noise2D']:
        assert noise['algorithm']['key'] == 'CONSTANT'
        assert noise['algorithm']['parameters'] == {'value': .125}
    if mode == 'revision':
        assert original['project']['seed'] == 0
        original['project']['seed'] = 1
    assert current == original, mode
    if mode == 'coalesced':
        assert case['builds'] == 1 and case['queries'] == 2
    preferences_before = json.loads((background / mode / 'preferences-before.json').read_text(encoding='utf-8'))
    preferences_after = json.loads((background / mode / 'preferences-after.json').read_text(encoding='utf-8'))
    assert preferences_before == preferences_after, mode
print('BACKGROUND_FIND_INDEPENDENT_PASS nine worker/lifecycle receipts / eighteen saved documents / preferences')
