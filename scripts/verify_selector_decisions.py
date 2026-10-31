"""Deferred independent selector bounds, exact callback routes and unchanged project replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('selector-decisions-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'heldQueriesBlocked', 'ownerChangesRetired', 'choiceChangesScoped',
            'hiddenReopenScoped', 'propertyOwnersScoped', 'callbacksBalanced', 'failureRetryScoped',
            'successorsPreserved', 'nullableRoutes', 'stateFreeRetry', 'emptyRowsPreserved', 'projectPreserved'):
    assert report[key] is True, key
for key in ('physicalDpiVerified', 'physicalDetachedVerified', 'gpuFontTextureVerified'):
    assert report[key] is False, key
assert len(report['cases']) == 24
assert {(c['form'], c['nullable'], c['scenario']) for c in report['cases']} == {
    (form, nullable, i) for form in ('resource', 'local') for nullable in (True, False) for i in range(6)}
globals_, scales = (1, .5, 1, 2.5, 2.5, 1), (1, 1, 1, 1, 2.5, 1)
identities = {}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1


for case in report['cases']:
    i, form, nullable = case['scenario'], case['form'], case['nullable']
    assert case['globalScale'] == globals_[i] and case['windowScale'] == scales[i]
    contains(case['popup'], case['query'])
    contains(case['popup'], case['list'])
    assert case['query']['y'] + case['query']['height'] <= case['list']['y'] + 1.1
    expected = {report['beta'] if form == 'resource' else 'beta'}
    if nullable:
        expected.add('none')
    assert set(case['ids']) == expected and all(case['ids'].values())
    assert len(set(case['ids'].values())) == len(expected)
    assert 0 < case['unfilteredRows'] < 96
    assert case['stride'] > 0 and case['stride'] == math.ceil(case['stride'])
    key = form, nullable
    if key not in identities:
        identities[key] = case['ids']
    assert identities[key] == case['ids']
assert identities['resource', True][report['beta']] != identities['resource', False][report['beta']]
assert identities['local', True]['beta'] != identities['local', False]['beta']
assert report['deliveries'] == [
    dict(picker='resource', value=report['beta']), dict(picker='resource', value=None),
    dict(picker='local', value='beta'), dict(picker='local', value=None),
    dict(picker='property-resource', value=report['beta']),
    dict(picker='property-local', value='beta'), dict(picker='unbound-local', value='beta')]
events = read('selector-events.json')
assert events['counts'] == dict(retained=4, unread=4, actionRequired=4, evicted=0)
assert len(events['entries']) == 4
assert {(e['code'], e['message']) for e in events['entries']} == {
    ('RESOURCE_CHOICE_FAILED', 'resource boundary'), ('RESOURCE_CHOICE_FAILED', 'resource successor'),
    ('DRAFT_CHOICE_FAILED', 'local boundary'), ('DRAFT_CHOICE_FAILED', 'local successor')}
for event in events['entries']:
    assert event['count'] == 1 and event['severity'] == 'ERROR' and event['subsystem'] == 'APPLICATION'
    assert not event['read'] and not event['dismissed'] and event['actionRequired'] and event['target'] is None
project = read('unchanged-project.json')
assert project == read('protected-project.json')
assert read('preferences.json') == read('preferences-before.json')
assert project['formatVersion'] == 2 and len(project['noise2D']) == 96
assert project['noise2D'][0]['uuid'] == report['alpha'] and project['noise2D'][1]['uuid'] == report['beta']
assert [n['id'] for n in project['noise2D'][:2]] == ['alpha', 'beta']
assert project['noise2D'][0]['algorithm']['parameters'] == {'value': .125}
assert project['noise2D'][1]['algorithm']['parameters'] == {'value': .25}
assert project['noise2D'][0]['seedOffset'] == 0
assert not project['noise3D'] and not project['worldGenerator']['layers']
print('Selector geometry, exact UUID/key/None callbacks, failure boundaries and unchanged project/preferences replay PASS')
