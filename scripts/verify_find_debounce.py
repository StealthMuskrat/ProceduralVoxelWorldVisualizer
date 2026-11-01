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
