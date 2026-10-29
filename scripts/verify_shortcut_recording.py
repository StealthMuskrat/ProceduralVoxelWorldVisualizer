"""Deferred recording/recovery report and independent saved preference expectations."""
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
report = json.loads((out / 'shortcut-recording-native.json').read_text(encoding='utf-8'))
user = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('completionSuppressed', 'focusCancelled', 'appFocusCancelled', 'textEditCancelled',
            'activeChangeCancelled', 'hideCancelled', 'hiddenDraftPreserved', 'writeRetry',
            'projectPreserved', 'cachedRows'):
    assert report[key] is True, key
assert 0 < report['submittedRows'] < 20
assert user['input']['shortcuts']['Find'] == report['find'] == 'Ctrl+Alt+F8'
assert user['input']['shortcuts']['Save'] == 'Ctrl+S'
assert user['appearance']['theme'] == report['theme'] == 'Light'
assert user['workspace']['width'] == report['width'] == 1234
print('SHORTCUT_RECORDING_INDEPENDENT_PASS exact saved binding / unrelated preferences / owned recording report')
