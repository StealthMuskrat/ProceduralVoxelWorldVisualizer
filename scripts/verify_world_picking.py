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

flight_root = Path(sys.argv[1]) / 'free-fly'
flight = json.loads((flight_root / 'free-fly-native.json').read_text())
flight_project = json.loads((flight_root / 'free-fly-project.json').read_text())
assert flight['formatVersion'] == 1 and flight['hit'] == [3, 0, 0, 1]
assert flight['entered'] == [7, 0, 0]
initial, moved = flight['initial'], flight['moved']
assert len(initial) == len(moved) == 6
assert all(math.isfinite(value) for value in initial + moved)
assert math.isclose(initial[0], 20.25, abs_tol=1e-12)
assert math.isclose(initial[1], .25, abs_tol=1e-12) and math.isclose(initial[2], .25, abs_tol=1e-12)
assert math.isclose(initial[3], math.pi / 2, abs_tol=1e-12) and initial[4:] == [0, 20]
assert math.isclose(moved[0], initial[0] - 2, abs_tol=1e-6)
assert all(math.isclose(a, b, abs_tol=1e-12) for a, b in zip(initial[1:], moved[1:]))
assert flight_project['worldGenerator']['layers'][0]['defaults']['action'] == 'SET'
assert flight_project['noise3D'][0]['algorithm']['parameters']['value'] == 1
assert all(flight[key] is True for key in ['entryConsumed', 'inspectorSuspended', 'posePreserved',
    'textEditingPaused', 'closeDidNotCapture', 'otherWindowPaused', 'otherEscapeIgnored',
    'tabPaused', 'explicitResume', 'escapeOrbit', 'closeReleased', 'viewOnly'])
print('FREE_FLY_INSPECTOR_PASS entry consumed / analytical movement and hit / paused Inspector typing / focus-tab safety / explicit resume / Escape / native cursor cleanup / view-only')

orbit_root = Path(sys.argv[1]) / 'orbit-input'
orbit = json.loads((orbit_root / 'orbit-input-native.json').read_text())
orbit_project = json.loads((orbit_root / 'orbit-project.json').read_text())
assert orbit['formatVersion'] == 1 and orbit['hit'] == [3, 0, 0, 1]
assert [row['kind'] for row in orbit['observations']] == ['orbit', 'pan', 'middleXZ', 'middleY']
for row in orbit['observations']:
    before, after = row['before'], row['after']
    assert len(before) == len(after) == 6
    assert all(math.isfinite(value) for value in before + after)
    expected = before.copy()
    dx, dy = row['dx'], row['dy']
    if row['kind'] == 'orbit':
        expected[3] -= dx * .008
        expected[4] = max(-1.5, min(1.5, expected[4] + dy * .008))
    elif row['kind'] == 'pan':
        speed = before[5] * .0015
        expected[0] += (-math.cos(before[3]) * dx - math.sin(before[3]) * dy) * speed
        expected[2] += (math.sin(before[3]) * dx - math.cos(before[3]) * dy) * speed
    elif row['kind'] == 'middleY':
        expected[1] -= dy * before[5] * .002
    else:
        expected[0] -= dx * before[5] * .002
        expected[2] -= dy * before[5] * .002
    assert all(math.isclose(a, b, rel_tol=0, abs_tol=1e-10) for a, b in zip(expected, after))
assert orbit_project['worldGenerator']['layers'][0]['defaults']['action'] == 'SET'
assert orbit_project['noise3D'][0]['algorithm']['parameters']['value'] == 1
assert all(orbit[key] is True for key in ['outsideCaptured', 'panWithoutMenu', 'shortContext',
    'longPressIgnored', 'stickyDrag', 'foreignIgnored', 'escapeRollback', 'focusCanceled',
    'tabCanceled', 'middleAxisLocked', 'wheelSuppressed', 'freshPickRecovered', 'viewOnly'])
print('ORBIT_INPUT_PASS independent orbit-pan-middle arithmetic / captured outside / pan-context separation / sticky drags / focus-tab-Escape cleanup / view-only')

slice_root = Path(sys.argv[1]) / 'slice-input'
slices = json.loads((slice_root / 'slice-input-native.json').read_text())
slice_project = json.loads((slice_root / 'slice-project.json').read_text())
assert slices['formatVersion'] == 1
assert [row['plane'] for row in slices['samples']] == ['X', 'Y', 'Z']

def slice_point(row, x, y):
    u = row['origin'][0] + x * row['side'] * row['step']
    v = row['origin'][1] + y * row['side'] * row['step']
    return {'X': [row['slice'], u, v], 'Y': [u, row['slice'], v],
            'Z': [u, v, row['slice']]}[row['plane']]

def affine(point):
    return point[0] + 10 * point[1] + 100 * point[2]

for row in slices['samples'] + [slices['pending'], slices['refreshed']]:
    expected = slice_point(row, *row['pointer'])
    assert all(math.isclose(a, b, rel_tol=0, abs_tol=1e-9) for a, b in zip(expected, row['point']))
    assert math.isclose(affine(expected), row['raw'], rel_tol=0, abs_tol=1e-9)
for row in slices['samples'] + [slices['refreshed']]:
    last = (row['side'] - 1) / row['side']
    assert math.isclose(affine(slice_point(row, 0, 0)), row['first'], rel_tol=0, abs_tol=1e-9)
    assert math.isclose(affine(slice_point(row, last, last)), row['last'], rel_tol=0, abs_tol=1e-9)
assert all(row['origin'] == [-10.25, 9.5] and row['side'] == 8 and row['slice'] == -3
           for row in slices['samples'])
before, after = slices['zoomBefore'], slices['zoomAfter']
assert before == [-7.25, 12.5, .75, 8]
assert math.isclose(after[2], .75 * math.exp(-.3), rel_tol=0, abs_tol=1e-12)
for axis in (0, 1):
    offset = slices['anchor'][axis] * before[3] - before[3] / 2
    assert math.isclose(before[axis] + offset * before[2], after[axis] + offset * after[2], abs_tol=1e-10)
assert [pan['button'] for pan in slices['pans']] == [0, 2]
for pan in slices['pans']:
    b, a = pan['before'], pan['after']
    assert math.isclose(a[0], b[0] - pan['du'] * b[3] * b[2], abs_tol=1e-10)
    assert math.isclose(a[1], b[1] - pan['dv'] * b[3] * b[2], abs_tol=1e-10)
assert (slices['pending']['plane'], slices['pending']['side'], slices['pending']['slice']) == ('X', 8, -3)
assert (slices['refreshed']['plane'], slices['refreshed']['side'], slices['refreshed']['slice']) == ('Z', 16, 9)
assert slices['picked'] == [math.floor(v) for v in slices['pending']['point']]
assert slice_project['noise3D'][0]['processing'][0]['parameters']['expression'] == 'x + 10*y + 100*z'
assert all(slices[key] is True for key in ['allPlanes', 'anchoredZoom', 'outsidePan', 'wheelSuppressed',
    'escapeRollback', 'foreignIgnored', 'focusCanceled', 'tabCanceled', 'requestSuperseded', 'pendingGeometry',
    'bookmarkGeometry', 'manualPreview', 'immutableAccepted', 'viewOnly'])
print('SLICE_INPUT_PASS independent XYZ affine grid-hover / anchored zoom / captured left-middle / cancellation / immutable pending bookmark geometry / view-only')

image_root = Path(sys.argv[1]) / 'image-gestures'
images = json.loads((image_root / 'image-gestures-native.json').read_text())
image_project = json.loads((image_root / 'image-gestures-project.json').read_text())
assert images['formatVersion'] == 1
b, a = images['before'], images['released']
w, h = images['pressDimensions']
assert images['pressDimensions'] != images['resizedDimensions']
assert images['releaseDelta'] == [80, 40]
assert math.isclose(a[0], b[0] - 80 / w * 256 * b[2], rel_tol=0, abs_tol=1e-9)
assert math.isclose(a[1], b[1] - 40 / h * 256 * b[2], rel_tol=0, abs_tol=1e-9)
assert a[2] == b[2]
assert [row['kind'] for row in images['samples']] == ['2D-left', '2D-context', 'slice-context']
for row in images['samples']:
    u, v = row['pointer']
    if row['kind'].startswith('2D'):
        expected = [u * 256 - 128, 0, v * 256 - 128]
        raw = expected[0] + 100 * expected[2]
        picked = [math.floor(expected[0]), math.floor(expected[2])]
    else:
        expected = [-10.25 + u * 6, -3, 9.5 + v * 6]
        raw = affine(expected)
        picked = [math.floor(x) for x in expected]
    assert all(math.isclose(x, y, rel_tol=0, abs_tol=1e-9) for x, y in zip(expected, row['point']))
    assert math.isclose(raw, row['raw'], rel_tol=0, abs_tol=1e-9)
    assert row['picked'] == picked
assert image_project['noise2D'][0]['processing'][0]['parameters']['expression'] == 'x + 100*z'
assert image_project['noise3D'][0]['processing'][0]['parameters']['expression'] == 'x + 10*y + 100*z'
assert all(images[key] is True for key in ['resizeStable', 'finalReleaseApplied', 'foreignBookmarkRetained',
    'escapeBookmarkRetained', 'collapseCanceled', 'heldWheelSuppressed', 'shortContexts',
    'longContextsIgnored', 'stickyContextsIgnored', 'foreignContextsIgnored',
    'focusContextsCanceled', 'buttonArbitration', 'acceptedGeometry', 'manualUnchanged', 'viewOnly'])
print('IMAGE_GESTURES_PASS independent press-size/release pan / accepted double coordinates / owned bookmarks / collapse / shared context arbitration / view-only')

file_root = Path(sys.argv[1]) / 'project-files'
files = json.loads((file_root / 'project-files-native.json').read_text())
assert files['formatVersion'] == 1
cases = {row['id']: row for row in files['cases']}
assert len(cases) == len(files['cases']) == 20
for intent in ['NEW', 'OPEN', 'EXIT']:
    for decision in ['SAVE', 'DISCARD', 'CANCEL']:
        row = cases[f'{intent}-{decision}']
        canceled = decision == 'CANCEL'
        seed = 41 if canceled or intent == 'EXIT' else 0 if intent == 'NEW' else 99
        assert row['seed'] == seed
        assert row['noiseCount'] == (0 if not canceled and intent in ['NEW', 'OPEN'] else 1)
        assert row['dirty'] == (canceled or intent == 'EXIT' and decision == 'DISCARD')
        assert row['phase'] == 'IDLE'
        assert row['replacements'] == (1 if not canceled and intent in ['NEW', 'OPEN'] else 0)
        assert row['exits'] == (1 if not canceled and intent == 'EXIT' else 0)
    for suffix in ['saved', 'retry']:
        saved = json.loads((file_root / f'{intent}-{suffix}.json').read_text())
        assert saved['project']['seed'] == 41 and saved['noise2D'][0]['id'] == 'owned'
    row = cases[f'{intent}-FAILED_SAVE_RETRY']
    assert row['seed'] == 41 and row['dirty'] is False and row['phase'] == 'IDLE'
    assert row['replacements'] == row['exits'] == 0
    assert (file_root / f'{intent}-blocked' / 'sentinel').read_text() == 'retained'
    row = cases[f'{intent}-PATH_CANCEL']
    assert row['seed'] == 41 and row['dirty'] is True and row['phase'] == 'IDLE'
    assert row['replacements'] == row['exits'] == 0
for name in ['GUARD_ESCAPE', 'PATH_ESCAPE', 'NAMED_SAVE_FAILURE']:
    row = cases[name]
    assert row['dirty'] is True and row['phase'] == 'IDLE' and row['exits'] == row['replacements'] == 0
assert cases['FAILED_OPEN_RETRY']['seed'] == 99 and cases['FAILED_OPEN_RETRY']['replacements'] == 1
assert cases['CLEAN_EXIT']['exits'] == 1 and cases['CLEAN_EXIT']['dirty'] is False
assert (file_root / 'named.json' / 'sentinel').read_text() == 'retained'
assert not list(file_root.glob('.voxel-save-*'))
print('PROJECT_FILES_PASS independent 20-case transition states / committed save bytes / retained failure destinations / no stale transition on retry / temp cleanup')

input_root = Path(sys.argv[1]) / 'input-bindings'
bindings = json.loads((input_root / 'input-binding-native.json').read_text())
binding_project = json.loads((input_root / 'input-binding-project.json').read_text())
assert bindings['formatVersion'] == 1
assert bindings['selected'] == ['first/extra', 'second/alpha', 'first/extra', 'second/alpha']
assert bindings['focused'] == ['first/bias/value', 'second/beta/value', 'first/bias/type',
    'first/bias/sourceKind', 'first/flag/value', 'first/shared/parameterUuid', 'second/earlier/itemUuid',
    'second/ambient/ambientKey', 'first/reference/noiseGeneratorUuid', 'first/reference/projection',
    'first/reference/slice', 'translate/x/value', 'translate/coordinates/value/1']
assert bindings['diagnosticInput'] == 'inputs/bias'
noise = binding_project['noise2D'][0]
first, second = noise['processing']
transform = noise['coordinateTransforms'][0]
assert noise['outputBinding']['itemUuid'] == first['uuid']
assert first['parameters']['expression'] == 'bias+extra+x+2*z'
assert second['parameters']['expression'] == 'alpha+beta'
assert first['inputs']['bias']['value'] == 3.25 and first['inputs']['extra']['value'] == 3
assert second['inputs']['alpha']['value'] == 7 and second['inputs']['beta']['value'] == 11
assert first['inputs']['flag']['value'] is True and first['inputs']['flag']['type'] == 'Boolean'
assert first['inputs']['shared']['parameterUuid'] == noise['sharedParameters'][0]['uuid']
assert first['inputs']['reference']['noiseGeneratorUuid'] == binding_project['noise2D'][1]['uuid']
assert second['inputs']['earlier']['itemUuid'] == first['uuid']
assert second['inputs']['ambient']['ambientKey'] == 'x'
assert transform['inputs']['x']['value'] == 4
assert transform['inputs']['coordinates']['type'] == 'Coordinates2D'
x, z = transform['inputs']['coordinates']['value']
x += transform['inputs']['x']['value']
expected = first['inputs']['bias']['value'] + first['inputs']['extra']['value'] + x + 2*z
assert bindings['before'] == 5 and bindings['after'] == expected == 26.25
assert all(bindings[key] is True for key in ['draftPreserved', 'navigationOnly', 'oneUndoRedo', 'foreignRejected'])
print('INPUT_BINDING_PASS independent saved typed bindings / exact component focus / one-shot per-item choices / transformed 26.25 / single Undo-Redo / draft ownership')

iso_root = Path(sys.argv[1]) / 'isosurface'
iso = json.loads((iso_root / 'isosurface-native.json').read_text())
iso_project = json.loads((iso_root / 'isosurface-project.json').read_text())
assert iso['formatVersion'] == 1
meshes = {row['name']: row for row in iso['meshes']}
assert len(meshes) == 6

def surface_vertices(row):
    raw = row['vertices']
    assert len(raw) % 21 == 0 and row['bytes'] == 4 * len(raw)
    assert all(math.isfinite(v) for v in raw)
    return [raw[i:i+7] for i in range(0, len(raw), 7)]

def surface_area(vertices):
    area = 0
    for i in range(0, len(vertices), 3):
        a, b, c = vertices[i:i+3]
        u, v = [b[k]-a[k] for k in range(3)], [c[k]-a[k] for k in range(3)]
        cross = [u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]]
        assert sum(cross[k]*a[k+3] for k in range(3)) > 0
        area += math.sqrt(sum(x*x for x in cross))/2
    return area

for name, expression, z, normal in [('positive','z - 2.25',2.25,-1),
    ('negative','2.25 - z',2.25,1),
    ('extreme','z < 3 ? -1.7976931348623157e308 : 1.7976931348623157e308',2.5,-1)]:
    row = meshes[name]
    assert iso_project['noise3D'][['positive','negative','extreme'].index(name)]['processing'][0]['parameters']['expression'] == expression
    vertices = surface_vertices(row)
    assert math.isclose(surface_area(vertices), 16, rel_tol=0, abs_tol=1e-6)
    assert all(v[2] == z and v[3:6] == [0,0,normal] and v[6] == 128 for v in vertices)
    assert row['samples'] == 125 and len(row['pixels']) == 25 and row['normalZ'] == normal
    expected = 255*(.35+.65*max(0,normal*.3/math.sqrt(1.25)))
    assert all(abs(pixel[0]-expected)<=2 and pixel[1:] == [0,0,255] for pixel in row['pixels'])
left, right = [surface_vertices(meshes[name]) for name in ['left','right']]
for row, origin in [(left,-2),(right,0)]:
    surface_area(row)
    assert all(abs(v[1]+.37*v[2]+.23*(v[0]+origin)-.8)<3e-7 for v in row)
assert {(v[1],v[2]) for v in left if v[0]==2} == {(v[1],v[2]) for v in right if v[0]==0}
sphere = surface_vertices(meshes['sphere'])
surface_area(sphere)
edges = {}
for i in range(0,len(sphere),3):
    for j in range(3):
        a, b = tuple(sphere[i+j][:3]), tuple(sphere[i+(j+1)%3][:3])
        edge = tuple(sorted((a,b)))
        edges[edge] = edges.get(edge,0)+1
assert edges and set(edges.values()) == {2}
for v in sphere:
    assert abs(sum(n*n for n in v[3:6])-1)<2e-6
    assert sum((v[k]-2)*v[k+3] for k in range(3))>0
print('ISOSURFACE_PASS independent affine areas / positive-complement normals / extreme finite interpolation / 75 lighting pixels / exact shared face / closed sphere edge incidence')
