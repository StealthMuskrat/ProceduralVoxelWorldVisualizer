"""Independent coordinate expectations for the native World picking fixture."""
import json
import math
import sys
from pathlib import Path

report = json.loads((Path(sys.argv[1]) / 'world-picking-native.json').read_text())
assert report['formatVersion'] == 2
hits = report['hits']
assert [hit['mode'] for hit in hits] == ['After', 'Before', 'Only Changes', 'Fallback']
for hit in hits:
    lower = math.floor(hit['centerX'] - hit['side'] / 2)
    assert hit['x'] == lower + hit['side'] - 1
    assert hit['y'] == hit['z'] == 0
    assert hit['finalType'] == 0  # SET then CLEAR; stage ghosts still select an inspectable cell.
assert all(report[key] is True for key in [
    'emptyMiss', 'obsoleteModeRejected', 'obsoleteZoomRejected',
    'closedPublicationRejected', 'viewOnly',
])
print('WORLD_PICKING_INDEPENDENT_PASS displayed cube faces / stage EMPTY and removal ghosts / obsolete mode-zoom-closure / view-only')

stream = report['stream']
assert [hit['mode'] for hit in stream['hits']] == ['After', 'Before', 'Only Changes', 'Travel']
for hit in stream['hits']:
    assert hit['x'] == (hit['chunkX'] + 1) * 32 - 1
    assert hit['y'] == hit['z'] == 0 and hit['finalType'] == 0
assert all(stream[key] is True for key in ['unloadedMiss', 'gpuReplacementRejected', 'epochReused'])
print('STREAMED_WORLD_PICKING_PASS drawn chunk faces / negative travel / unshown miss / GPU replacement / stage ghosts')
