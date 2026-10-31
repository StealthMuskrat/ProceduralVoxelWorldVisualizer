"""Deferred independent native trace lifetime, action eligibility and complete project checks."""
from copy import deepcopy
from pathlib import Path
import json
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('trace-lifetime-native.json')
assert report['formatVersion'] == 1
assert report['fixture'] == 'native-inspector-logs-synchronous-refresh'
assert report['physicalDpiVerified'] is False and report['backgroundRefreshVerified'] is False
for key in ('sourceGuardExpired', 'heldRefreshRejected', 'explicitRefreshRebound',
            'historicalCopyExact', 'privatePreserved', 'revisionPreserved'):
    assert report[key] is True, key
assert report['refreshes'] == 1 and report['retainedSnapshots'] == 2
assert report['raw'] == dict(X='7', Y='1', Z='-2')
phases = ('initial', 'saved', 'failed-open', 'identical-open', 'changed-open',
          'refreshed', 'refreshed-saved', 'retained-original')
cases = report['cases']
assert len(cases) == 24
assert [(row['phase'], row['form']) for row in cases] == [(phase, form) for phase in phases for form in range(3)]
assert len({row['revision'] for row in cases}) == 1
for row in cases:
    stale = row['phase'] in ('identical-open', 'changed-open', 'retained-original')
    assert row['freshness'] == ('PROJECT_CHANGED' if stale else 'CURRENT')
    assert row['reopened'] is stale and row['sourceCurrent'] is (not stale)
    assert row['controlAvailable'] is (row['form'] != 1 and not stale)
    if row['form'] == 1:
        assert row['traceRows'] == 0
    else:
        assert row['traceRows'] > 0
    assert row['finalType'] == (0 if row['phase'] in ('refreshed', 'refreshed-saved') else 1)
source = read('trace-source.json')
current = read('trace-current.json')
expected = deepcopy(source)
expected['worldGenerator']['layers'][0]['defaults']['action'] = 'CLEAR'
assert current == expected == read('current-after.json')
assert source == read('saved-copy.json')
assert read('preferences-before.json') == read('preferences.json')
old, new = read('original-trace.json'), read('current-trace.json')
assert old['position'] == new['position'] == dict(x=2, y=0, z=3)
assert old['finalType'] == 1 and new['finalType'] == 0
assert [step['action'] for step in old['steps']] == ['SET', 'NO_CHANGE']
assert [step['action'] for step in new['steps']] == ['CLEAR', 'NO_CHANGE']
for trace in (old, new):
    for index, step in enumerate(trace['steps']):
        assert step['field'] == (2 + 2 * (2 + 1) + 3) * 2 == 22
        comparison = step['details']['comparisons'][-1]
        assert comparison == dict(property='threshold', left=22.0, operator='>=', right=float((10, 30)[index]), passed=index == 0)
assert 'Compare threshold: 22.0 >= 30.0 : rejected' in (out / 'original-details.txt').read_text(encoding='utf-8')
print('Trace lifetime Inspector/Logs state routes, stale source controls, owned Refresh and exact persisted/analytical replay PASS')
