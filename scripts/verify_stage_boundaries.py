"""Deferred independent stage selector geometry, committed catalog and isolated selection replay."""
from pathlib import Path
import json
import math
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('stage-boundaries-native.json')
assert report['formatVersion'] == 1 and report['fontSource'] == 'synthetic-global-window-fixture'
for key in ('escapePreserved', 'modeQueryLifetimeScoped', 'hiddenReopenScoped', 'documentLifetimeScoped',
            'missingUndoRestored', 'emptyFinalDistinct', 'disabledSelectable', 'callbacksBalanced',
            'failureRetryScoped', 'catalogCached', 'privatePreserved'):
    assert report[key] is True, key
assert report['physicalDpiVerified'] is False and report['gpuPreviewVerified'] is False
assert len(report['cases']) == 18
assert {(c['mode'], c['scenario']) for c in report['cases']} == {
    (mode, i) for mode in ('Before', 'After', 'Only Changes') for i in range(6)}


def contains(outer, inner):
    for box in (outer, inner):
        assert all(math.isfinite(v) for v in box.values())
        assert box['width'] > 0 and box['height'] > 0
    for axis, size in (('x', 'width'), ('y', 'height')):
        assert inner[axis] >= outer[axis] - 1.1
        assert inner[axis] + inner[size] <= outer[axis] + outer[size] + 1.1


ids = {}
for case in report['cases']:
    i = case['scenario']
    assert case['globalScale'] == (1, .5, 1, 2.5, 2.5, 1)[i]
    assert case['windowScale'] == (1, 1, 1, 1, 2.5, 1)[i]
    contains(case['popup'], case['query'])
    contains(case['popup'], case['list'])
    assert case['rows'] == 1 and 0 < case['allSubmitted'] < 240
    assert case['stride'] > 0 and case['stride'] == math.ceil(case['stride'])
    assert set(case['ids']) == {report['clear']} and all(case['ids'].values())
    if case['mode'] not in ids:
        ids[case['mode']] = case['ids']
    assert ids[case['mode']] == case['ids']
assert len({value[report['clear']] for value in ids.values()}) == 3
assert report['selections'] == [dict(mode=mode, uuid=report['clear']) for mode in
                                ('Before', 'After', 'Only Changes', 'Before')] + [
                                    dict(mode='After', uuid=report['disabled'])]
project = read('stage-project.json')
assert project == read('after.json')
assert read('preferences-before.json') == read('preferences.json')
catalog = []
for layer in project['worldGenerator']['layers']:
    catalog.append(dict(uuid=layer['uuid'], layerUuid=layer['uuid'], kind='layer', id=layer['id'],
                        layerId=layer['id'], enabled=layer['enabled']))
    catalog.extend(dict(uuid=op['uuid'], layerUuid=layer['uuid'], kind='operation', id=op['id'],
                        layerId=layer['id'], enabled=layer['enabled'] and op['enabled'])
                   for op in layer['operations'])
assert report['catalog'] == catalog and len(catalog) == 240
assert len({row['uuid'] for row in catalog}) == 240
assert next(row for row in catalog if row['uuid'] == report['disabled'])['enabled'] is False
events = read('stage-events.json')
assert events['counts'] == dict(retained=1, unread=1, actionRequired=1, evicted=0)
event, = events['entries']
assert event['code'] == 'DRAFT_CHOICE_FAILED' and event['message'] == 'stage route boundary'
assert event['count'] == 1 and event['severity'] == 'ERROR' and event['subsystem'] == 'APPLICATION'
assert event['actionRequired'] and not event['read'] and not event['dismissed'] and event['target'] is None
print('Stage selector clipped geometry, exact catalog/UUID routes, owned retry and project isolation PASS')
