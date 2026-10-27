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

proxy_root = Path(sys.argv[1]) / 'lod-proxy'
proxy = json.loads((proxy_root / 'world-lod-picking-native.json').read_text())
project = json.loads((proxy_root / 'world-lod-project.json').read_text())
assert proxy['formatVersion'] == 4
assert [layer['defaults']['action'] for layer in project['worldGenerator']['layers']] == ['SET', 'SET', 'CLEAR']
plane = next(noise for noise in project['noise3D'] if noise['id'] == 'plane')
assert plane['processing'][0]['parameters']['expression'] == 'x == 100 ? 1/(x-100) : x == 28 ? 1 : -1'
# Half of the 2x2x2 cell group is occupied: inclusive majority fills x=29,
# although exact preview occupancy is at x=28. The later fill/CLEAR leaves x=29 solid.
occupied = sum(x == 28 for x in (28, 29) for y in (2, 3) for z in (2, 3))
assert occupied * 2 == 2 ** 3
expected = [('After', 29, True, 1), ('Only Changes', 29, True, 1), ('Refined', 28, False, 0)]
assert len(proxy['observations']) == len(expected)
for row, (mode, x, coarse, final_type) in zip(proxy['observations'], expected):
    assert (row['mode'], row['x'], row['y'], row['z'], row['proxy'], row['finalType']) == (mode, x, 2, 2, coarse, final_type)
    assert 0 < row['traceRows'] <= 30
    if coarse:
        assert 'X 29, Y 2, Z 2' in row['hint'] and 'exact preview' in row['hint'] and 'full world' in row['hint']
    else:
        assert row['hint'] == ''
assert all(proxy[key] is True for key in ['coordinateTraceClearedHint', 'streamMissClearedTrace',
    'fixedMissClearedTrace', 'retainedLogs', 'coordinateTraceRecovered', 'sceneEpochReused', 'viewOnly'])
print('WORLD_LOD_PICKING_INDEPENDENT_PASS half-occupied proxy / unchanged occupied full world / exact refinement / Inspector miss recovery / retained Logs')

coordinate = proxy['coordinateWork']
assert all(coordinate[key] is True for key in ['pendingVisible', 'cancelReleasedQueue', 'editCanceled',
    'replacementBounded', 'runtimeFailureRetained', 'runtimeRetry', 'queueRejected', 'queueRetry', 'tabIndependent'])
print('INSPECTOR_COORDINATE_TASK_PASS native pending-cancel / XYZ edit / one queued replacement / runtime failure / queue retry / tab independence')

draft = coordinate['draft']
assert draft['edited'] == [30, 2, 2] and draft['restored'] == [29, 2, 2]
assert all(draft[key] is True for key in ['lateWorldPickRejected', 'restoreWithoutWork',
    'restoreCanceledPending', 'closedWorldPickRejected', 'logsUnchanged'])
print('INSPECTOR_COORDINATE_DRAFT_PASS typed XYZ survives late World hit / restore without work / queued cancellation / native window closure')

world_work = draft['worldWork']
assert world_work['retryHit'] == [3, 0, 0] and world_work['retryFinalType'] == 1
assert all(world_work[key] is True for key in ['replacementBounded', 'cancelReleasedQueue',
    'queueRejected', 'queueRetry', 'cameraCanceled', 'tabCanceled', 'logsRetained'])
print('INSPECTOR_WORLD_TASK_PASS one queued pick / Cancel Pick / real full-queue retry / camera-tab reclamation / retained Logs')

fresh_root = Path(sys.argv[1]) / 'inspector-freshness'
fresh = json.loads((fresh_root / 'inspector-freshness-native.json').read_text())
before = json.loads((fresh_root / 'freshness-before.json').read_text())
after = json.loads((fresh_root / 'freshness-after.json').read_text())
empty = json.loads((fresh_root / 'freshness-empty.json').read_text())
assert fresh['formatVersion'] == 1
assert before['project']['uuid'] == after['project']['uuid'] != empty['project']['uuid']
assert before['worldGenerator']['layers'][0]['uuid'] == after['worldGenerator']['layers'][0]['uuid']
assert before['worldGenerator']['layers'][0]['defaults']['action'] == 'SET'
assert after['worldGenerator']['layers'][0]['defaults']['action'] == 'CLEAR'
assert before['noise3D'][0]['algorithm']['parameters']['value'] == 1
assert empty['noise3D'] == [] and empty['voxelTypes'] == [] and empty['worldGenerator']['layers'] == []
assert fresh['initial'] == [3, 0, 0, 1] and fresh['refreshed'] == [3, 0, 0, 0]
assert fresh['entered'] == [7, 0, 0] and fresh['newProjectTrace'] == [7, 0, 0, 0]
assert all(fresh[key] is True for key in ['projectChangeCanceled', 'refreshPreservedDraft',
    'refreshViewOnly', 'manualPreviewUnchanged', 'oldSnapshotImmutable', 'sourceLinksRefreshed',
    'foreignReadOnly', 'foreignRefreshUnavailable', 'newTraceViewOnly', 'emptyTraceCountersReset'])
print('INSPECTOR_FRESHNESS_PASS committed SET-CLEAR / queued cancellation / refresh preserves XYZ / changed-source link recovery / foreign read-only / new empty-project trace')
