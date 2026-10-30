"""Deferred independent saved-scale, inheritance and native helper font-size replay."""
import json
import math
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'ui-scale-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('hiddenInvalidBlocked', 'cachedFeedback', 'hiddenDraftRetained', 'staleRevert',
            'failedSaveRetry', 'canonicalAfterSave', 'inheritRemovedEntry',
            'unknownEntryRetained', 'projectPreserved'):
    assert report[key] is True, key
assert report['physicalDpiVerified'] is False
assert report['allProductionWindowsExercised'] is False
subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                str(out / 'user-config.json')], check=True)
saved = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
appearance = saved['appearance']
assert appearance['globalScale'] == 1.25
assert appearance['windowScales'] == {'Resources': 1.1, 'Logs': 2.0, 'Future Window': 1.75}
assert 'Help' not in appearance['windowScales']
assert appearance['theme'] == 'Light' and saved['workspace']['width'] == 1234
assert saved['input']['shortcuts']['Profiler'] == 'Alt+F9'
assert saved['performance']['cacheMiB'] == 17
registry = json.loads((root / 'registries/ui-scale-targets.json').read_text(encoding='utf-8'))
assert registry['registryVersion'] == 2 and registry['minimum'] == .5 and registry['maximum'] == 2.5
assert len(registry['windows']) == len(set(registry['windows'])) == 19
assert set(report['fontSizes']) == set(registry['windows'])
assert report['baseFontSize'] > 0 and math.isfinite(report['baseFontSize'])
for name, measured in report['fontSizes'].items():
    expected = report['baseFontSize'] * appearance['globalScale'] * appearance['windowScales'].get(name, 1)
    assert math.isfinite(measured) and math.isclose(measured, expected, rel_tol=1e-6, abs_tol=1e-4), (name, measured, expected)
periodic = json.loads((out / 'periodic-preferences.json').read_text(encoding='utf-8'))
assert periodic['appearance']['globalScale'] == 1 and periodic['appearance']['windowScales'] == {}
assert 'files' not in saved and not list(out.rglob('.user-config-*.tmp'))
print('UI scale exact saved values, explicit inheritance, extension preservation and native helper font metrics PASS')
