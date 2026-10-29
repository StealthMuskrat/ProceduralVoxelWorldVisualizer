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
              'engineRetained'):
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
