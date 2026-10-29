"""Deferred replay of theme ownership/recovery and raw cached contrast evidence."""
import json
import math
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'theme-workspace-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for field in ('externalStylePreserved', 'explicitResume', 'staleBaseRevert', 'failedWriteRetry',
              'resetAndStartingColorPrivate', 'cachedFeedbackAndRows', 'readableInspector',
              'collapsedContent', 'projectPreserved'):
    assert report[field] is True, field
assert 0 < report['submittedRows'] < 20
preferences = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
appearance = preferences['appearance']
assert appearance['theme'] == 'Light' and appearance['globalScale'] == 1.25
assert preferences['workspace']['width'] == 1234
assert preferences['input']['shortcuts']['Profiler'] == 'Alt+F9'
registry = json.loads((root / 'registries/theme-palette.json').read_text(encoding='utf-8'))
keys = {color['name']: str(color['key']) for color in registry['colors']}
assert set(appearance['colors']) == {keys['Header']}
for actual, expected in zip(appearance['colors'][keys['Header']], [.7, .8, .9, .5]):
    assert math.isclose(actual, expected, abs_tol=1e-7)
assert set(appearance['statusColors']) == {'Warning'}
for actual, expected in zip(appearance['statusColors']['Warning'], [.4, .2, 0, 1]):
    assert math.isclose(actual, expected, abs_tol=1e-7)
periodic = json.loads((out / 'periodic-preferences.json').read_text(encoding='utf-8'))
assert periodic['appearance']['theme'] == 'Dark'
assert periodic['appearance']['colors'] == {} and periodic['appearance']['statusColors'] == {}
contrast = json.loads((out / 'theme-workspace-contrast.json').read_text(encoding='utf-8'))
low = contrast['profiles'][0]
assert low['colors'][keys['Text']] == low['colors'][keys['WindowBg']]
assert next(c['ratio'] for c in low['checks'] if c['label'] == 'Text / WindowBg') == 1
subprocess.run([sys.executable, str(root / 'scripts/verify_theme.py'), str(out / 'user-config.json'),
                str(out / 'theme-workspace-contrast.json'), '--profiles',
                'Private low contrast', 'Saved preview'], check=True)
assert not list(out.rglob('.user-config-*.tmp'))
print('Theme preview ownership, retry, cached contrast and saved preference replay PASS')
