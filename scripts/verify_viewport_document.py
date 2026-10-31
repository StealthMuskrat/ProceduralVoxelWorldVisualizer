"""Deferred independent document/source/epoch/pixel and persisted-value receipt checks."""
from pathlib import Path
import json
import sys

out = Path(sys.argv[1])


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


report = read('viewport-document-native.json')
assert report['formatVersion'] == 1
assert report['fixture'] == 'direct-document-session-native-gl'
assert report['physicalDpiVerified'] is False and report['fileDialogUxVerified'] is False
for key in ('manualSameDocumentRetained', 'sameUuidRevisionReplacement',
            'pendingInspectorCanceled', 'queuedPublicationRejectedBeforeDraw',
            'immutableImageRetained', 'privateFixturePreserved', 'preferencesPreserved'):
    assert report[key] is True, key
assert len(report['before']) == len(report['retired']) == len(report['afterNew']) == 3
assert report['before'][0]['noise'] is not None
assert report['before'][0]['acceptedImage'] and report['before'][0]['cacheRows'] > 0
assert report['before'][2]['stage'] is not None and report['before'][2]['stageMode'] == 'After'
for kind, (before, retired, new) in enumerate(zip(report['before'], report['retired'], report['afterNew'])):
    assert before['kind'] == retired['kind'] == new['kind'] == kind
    assert before['epoch'] < retired['epoch'] < new['epoch']
    for row in (retired, new):
        assert row['noise'] is None and row['stage'] is None and row['stageMode'] == 'Final'
        assert row['cacheRows'] == row['cacheBytes'] == 0
        assert not any(row[key] for key in ('force', 'scene', 'volumeSnapshot', 'pendingVolume', 'acceptedImage', 'acceptedSlice'))
    assert retired['completed'] is False
    assert new['completed'] is (kind == 2)
assert report['replacementSubmission'] > report['oldSubmission'] > 0
for key in ('occupiedPixel', 'pendingPixel', 'emptyPixel'):
    pixel = report[key]
    assert len(pixel) == 4 and all(isinstance(value, int) and 0 <= value <= 255 for value in pixel)
assert report['occupiedPixel'][0] > 100 and max(report['occupiedPixel'][1:3]) < 40
for key in ('pendingPixel', 'emptyPixel'):
    assert not (report[key][0] > 100 and max(report[key][1:3]) < 40)
source, committed, replacement, after, empty = map(read, ('source.json', 'committed.json', 'replacement.json', 'replacement-after.json', 'empty.json'))
assert source['project']['uuid'] == committed['project']['uuid'] == replacement['project']['uuid'] != empty['project']['uuid']
assert source['worldGenerator']['layers'][0]['defaults']['action'] == 'SET'
assert committed['worldGenerator']['layers'][0]['defaults']['action'] == replacement['worldGenerator']['layers'][0]['defaults']['action'] == 'CLEAR'
expected_committed = json.loads(json.dumps(source))
expected_committed['worldGenerator']['layers'][0]['defaults']['action'] = 'CLEAR'
assert expected_committed == committed
expected_replacement = json.loads(json.dumps(committed))
expected_replacement['noise2D'][0]['algorithm']['parameters']['value'] = .5
expected_replacement['noise3D'][0]['algorithm']['parameters']['value'] = .75
assert expected_replacement == replacement == after
assert empty['noise2D'] == empty['noise3D'] == empty['voxelTypes'] == empty['worldGenerator']['layers'] == []
assert read('preferences-before.json') == read('preferences.json')
print('Viewport document lifetime, manual retention, exact epochs/source selections, queued request gate and persistence PASS')
