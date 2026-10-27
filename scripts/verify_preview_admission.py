import json,sys
from pathlib import Path
r=json.loads((Path(sys.argv[1])/'preview-admission-native.json').read_text())
assert r['formatVersion']==1 and r['workers']==2 and r['queueCapacity']==8
assert 'configured budget' in r['budgetMessage'] and 'queue is full' in r['queueMessage']
assert r['raw']==.25
assert all(r[k] is True for k in ['retainedImage','contextRetry','toolbarRetry','otherViewsUnblocked','viewOnly'])
print('PREVIEW_ADMISSION_PYTHON_PASS native retry report / exact constant output')
