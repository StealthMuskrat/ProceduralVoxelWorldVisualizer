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

from decimal import Decimal, localcontext
volume_root = Path(sys.argv[1]) / 'volume-encoding'
volume = json.loads((volume_root / 'volume-encoding-native.json').read_text())
volume_project = json.loads((volume_root / 'volume-encoding-project.json').read_text())
assert volume['formatVersion'] == 1 and len(volume['cases']) == len(volume_project['noise3D']) == 23
volume_ids = set()
for row, noise in zip(volume['cases'], volume_project['noise3D']):
    volume_ids.add(row['id'])
    assert noise['id'] == row['id'] and noise['processing'][0]['parameters']['expression'] == row['expression']
    settings, enc, transfer = row['settings'], row['encoding'], row['transfer']
    assert enc['scale'] > 0 and all(math.isfinite(v) for v in enc.values())
    assert all(math.isfinite(v) for v in transfer.values()) and len(row['texels']) == 64
    assert row['sampleCount'] == (0 if row['id'] == 'zero-opacity' else 64)
    assert all(math.isfinite(v) for v in row['texels'])
    desired = [row['coefficient'] * (.5 + (z+.5)/16) if row['kind'] == 'gradient' else row['coefficient']
               for z in range(4) for y in range(4) for x in range(4)]
    for encoded, canonical in zip(row['texels'], desired):
        decoded = max(enc['min'], min(enc['max'], enc['offset'] + encoded*enc['scale']))
        assert math.isclose(decoded, canonical, rel_tol=2e-7, abs_tol=0)
    with localcontext() as context:
        context.prec = 1000
        low, high = Decimal.from_float(settings['min']), Decimal.from_float(settings['max'])
        normalized = [float(max(Decimal(0), min(Decimal(1), (Decimal.from_float(v)-low)/(high-low))))
                      if v >= settings['threshold'] else 0 for v in desired]
    mean = (normalized[0]+normalized[-1])/2
    assert math.isclose(row['mean'], mean, rel_tol=0, abs_tol=1e-12)
    o, d = row['origin'], row['direction']
    assert math.isclose(sum(v*v for v in d), 1, rel_tol=0, abs_tol=1e-12)
    enter, leave = 0, math.inf
    for a, b in zip(o, d):
        if abs(b) < 1e-12:
            if a < 0 or a > 4:
                leave = -math.inf
                break
        else:
            t0, t1 = sorted([-a/b, (4-a)/b])
            enter, leave = max(enter,t0), min(leave,t1)
    path = max(0, leave-enter)
    assert math.isclose(path,row['path'],rel_tol=0,abs_tol=1e-10)
    transmittance = math.exp(-mean*min(10,settings['opacity'])*path)
    assert math.isclose(transmittance,row['transmittance'],rel_tol=0,abs_tol=1e-12)
    expected = [255*(1-transmittance+transmittance*.055),255*transmittance*.075,255*transmittance*.1]
    assert all(abs(a-b)<=2 for a,b in zip(row['pixel'][:3],expected)) and row['pixel'][3] == 255
assert {'ordinary','zero-opacity','huge-half','huge-quarter','tiny-min','tiny-half','huge-filtered',
    'tiny-filtered','wide-zero','wide-negative-filtered','wide-negative-accepted','adjacent-upper',
    'adjacent-lower','normal-adjacent-upper','normal-adjacent-lower','normal-adjacent-filtered','maximum-opacity','parallel-miss','parallel-boundary','inside','diagonal',
    'gradient-huge','gradient-tiny'} == volume_ids
print('VOLUME_ENCODING_PASS independent Decimal transfer / decoded canonical texels / 23 Beer-Lambert pixel cases / slab rays / threshold signs / control-only reuse')

import struct
surface_root = Path(sys.argv[1]) / 'surface-streaming'
surface = json.loads((surface_root / 'surface-streaming-native.json').read_text())
surface_project = json.loads((surface_root / 'surface-streaming-project.json').read_text())
packed = (surface_root / 'surface-packed.bin').read_bytes()
assert surface['formatVersion'] == 1 and len(packed) == surface['packedBytes'] and len(packed) % 48 == 0
assert surface['surfaceUnits'] == 128 and surface['voxelUnits'] == 2
vertices = []
for i in range(0,len(packed),16):
    x,y,z,nx,ny,nz,ao,pad,material = struct.unpack_from('<3H3bBHI',packed,i)
    assert 0 <= x <= 4096 and 0 <= y <= 4096 and z == round(16.257*128)
    assert (nx,ny,nz,ao,pad,material) == (0,0,-127,255,0,128)
    vertices.append([x/128,y/128,z/128,0,0,-1,128])
assert math.isclose(surface_area(vertices),1024,rel_tol=0,abs_tol=1e-9)
assert surface['left'] == [255,0,0,255] and surface['right'] == [0,255,0,255]
assert 0 < surface['maximumUploadBytes'] <= 4096 and surface['relocatedBytes'] > 0
assert surface['fallbackHash'] == surface['compactedHash'] and surface['indirectParity'] is True
stages = surface['stages']
assert [row['name'] for row in stages] == ['stream-origin','stream-positive','stream-negative','stream-return']
assert [row['chunk'] for row in stages] == [[0,0,0],[1,0,0],[-1,0,0],[-1,0,0]]
assert stages[0]['epoch'] == stages[1]['epoch'] == stages[2]['epoch'] != stages[3]['epoch']
for row in stages:
    assert row['resident'] <= 27 and row['cpuBytes'] + row['reserved'] <= 256 * 1024**2
    assert all(abs(a-b)<=2 for a,b in zip(row['pixel'],[45.15,89.25,43.75,255]))
assert all(abs(a-b)<=2 for a,b in zip(surface['fixedPixel'],[45.15,89.25,43.75,255]))
assert surface['viewOnly'] is True and surface['fixedReturn'] is True
assert surface_project['noise3D'][0]['processing'][0]['parameters']['expression'] == 'z - 16.25'
print('SURFACE_STREAMING_PASS independent packed quantum/normal/area / mixed arena pixels / bounded publication / compaction parity / signed travel epochs / fixed return / view-only')

# Independent staged volume texture/optics and UI publication replay.
upload_root = Path(sys.argv[1]) / 'volume-upload'
vu = json.loads((upload_root / 'volume-upload-native.json').read_text())
assert vu['formatVersion'] == 1 and vu['side'] == 17 and vu['byteBudget'] == 79
assert sum(vu['writes']) == 4 * 17**3 and all(0 < n <= 79 and n % 4 == 0 for n in vu['writes'])
assert len(vu['texels']) == 17**3
for i, value in enumerate(vu['texels']):
    expected = struct.unpack('<f', struct.pack('<f', ((i % 17) + .5) / 17))[0]
    assert struct.pack('<f', value) == struct.pack('<f', expected), (i, value, expected)
assert vu['encoding']['offset'] == 0 and vu['encoding']['scale'] == 1
transmittance = math.exp(-.5 * .1 * 17)
assert abs(vu['transmittance'] - transmittance) < 1e-14
expected_pixel = [255 * (1 - transmittance + transmittance * .055),
                  255 * transmittance * .075, 255 * transmittance * .1, 255]
assert all(abs(a-b) <= 2 for a, b in zip(vu['pixel'], expected_pixel))
assert len(vu['retainedHash']) == 64
assert 0 < vu['uiMaxBytes'] <= 16384
assert vu['uiVersions'] == [1, 2, 3]
assert vu['uiOrigins'] == [[0, 0, 0], [-24, -24, -24], [32, 32, 32]]
assert all(vu[k] for k in ['unpackRestored', 'rejectionRetained', 'cancelRetained', 'closed',
                          'uiCanceled', 'uiGpuRejected', 'viewOnly', 'sceneTransitionRetained'])
vproject = json.loads((upload_root / 'volume-upload-project.json').read_text())
assert [n['processing'][0]['parameters']['expression'] for n in vproject['noise3D']] == ['1', '0']
print('VOLUME_UPLOAD_PASS independent float texels / byte sums / analytical optics / accepted origins and versions / cancellation-admission-retry / view-only')

# Independent moving scalar-window addressing, transfer, cache and publication replay.
vs_root = Path(sys.argv[1]) / 'volume-streaming'
vs = json.loads((vs_root / 'volume-streaming-native.json').read_text())
assert vs['formatVersion'] == 1
binary = (vs_root / 'volume-window.bin').read_bytes()
assert len(binary) == 4 * 64**3
for i, (value,) in enumerate(struct.iter_unpack('<f', binary)):
    x, y, z = i % 64 - 31.5, i // 64 % 64 - 31.5, i // (64*64) - 31.5
    assert value == .5 + (x + 2*y + 4*z) / 512, (i, value)
affine = vs['affine']
assert affine['bounds'] == {'side': 64, 'x': -32, 'y': -32, 'z': -32}
assert affine['sampledChunks'] == 8 and affine['uploadBytes'] == 4 * 64**3
assert 0 < affine['maximumUploadBytes'] <= 16384
transmittance = math.exp(-.5 * .01 * 64)
expect = [255 * (1-transmittance+transmittance*.055), 255*transmittance*.075, 255*transmittance*.1, 255]
assert all(abs(a-b) <= 2 for a,b in zip(affine['pixel'], expect))
stages = vs['stages']
assert [s['name'] for s in stages] == ['origin','positive-x','negative-x','return','positive-y','positive-z',
                                      'manual-retained','regenerated','cancel-successor','stream-return']
assert [s['origin'] for s in stages] == [[-32,-32,-32],[0,-32,-32],[-64,-32,-32],[-32,-32,-32],
                                        [-32,0,-32],[-32,0,0],[32,0,0],[32,0,0],[-128,0,0],[-32,-32,-32]]
assert stages[0]['sampledChunks'] == 8
assert (stages[1]['sampledChunks'], stages[1]['reusedChunks']) == (4,4)
assert (stages[3]['sampledChunks'], stages[3]['reusedChunks']) == (0,8)
assert len({s['generation'] for s in stages[:7]}) == 1
assert all(s['compilations'] == 1 for s in stages[:7])
assert stages[7]['generation'] > stages[6]['generation'] and stages[7]['compilations'] == 2
assert stages[-1]['generation'] > stages[8]['generation'] and stages[-1]['sampledChunks'] == 8
for i, s in enumerate(stages):
    assert s['sampledChunks'] + s['reusedChunks'] == 8
    assert 0 <= s['cacheBytes'] <= 64*1024*1024
    value = 1 if i < 7 else 0
    assert s['value'] == value
    t = math.exp(-value * .01 * 64)
    # Heatmap endpoint at t=1 is (127,0,0); a zero field has zero optical density.
    base = [127,0,0] if value else [0,0,127]
    expected = [(1-t)*base[c] + t*255*[.055,.075,.1][c] for c in range(3)] + [255]
    assert all(abs(a-b) <= 2 for a,b in zip(s['pixel'], expected)), s
assert all(vs[k] for k in ['navigationViewOnly','postEditViewOnly','fixedReturn','transferReused',
                          'canceledUpload','gpuRejected','queueRetry'])
assert 0 < vs['maximumUiBytes'] <= 16384
vproject = json.loads((vs_root / 'volume-streaming-project.json').read_text())
assert vproject['noise3D'][0]['processing'][0]['parameters']['expression'] == '0'
print('VOLUME_STREAMING_PASS independent seam-spanning texels / optics / signed window origins / cache reuse / compiled source epochs / manual regeneration / cancellation-queue-budget recovery / fixed return')

# Production streaming must reuse resident overlap without changing source epochs or display results.
# GPU reuse sees only the current resident: the two-chunk negative jump is disjoint.
# The manual authored edit revalidates the unchanged accepted window with one full resident copy.
assert [s['copiedBytes'] for s in stages[:7]] == [0, 524288, 524288, 1048576, 1572864, 2097152, 3145728]
assert stages[7]['copiedBytes'] == stages[6]['copiedBytes']  # Source regeneration requires fresh texels.
from verify_volume_overlap import verify as verify_overlap
verify_overlap(Path(sys.argv[1]) / 'volume-overlap')

# Independently replay effective scalar visibility, retained-source texels and optical output.
vp_root = Path(sys.argv[1]) / 'volume-pressure'
vp = json.loads((vp_root / 'volume-pressure-native.json').read_text())
assert vp['formatVersion'] == 1
pressure_stages = vp['stages']
assert [s['name'] for s in pressure_stages] == [
    'configured', 'unit-grid-retained', 'reduced', 'cache-released', 'deferred', 'paused-upload',
    'resumed-new-source', 'restored', 'cpu-recovered', 'budget-retry', 'paused-queue', 'queue-resumed']
assert [s['level'] for s in pressure_stages] == [0,1,2,3,4,5,3,0,2,0,5,3]
assert [s['side'] for s in pressure_stages] == [64,64,32,32,32,32,32,64,32,64,64,32]
assert [s['origin'] for s in pressure_stages] == [
    [-32,-32,-32], [-32,-32,-32], [0,0,0], [0,0,0], [0,0,0], [0,0,0],
    [-32,0,0], [-64,-32,-32], [0,0,0], [0,-32,-32], [0,-32,-32], [64,0,0]]
assert [s['version'] for s in pressure_stages] == [1,1,2,2,2,2,3,4,5,8,8,9]
assert len({s['generation'] for s in pressure_stages[:6]}) == 1
assert pressure_stages[6]['generation'] > pressure_stages[5]['generation']
assert len({s['generation'] for s in pressure_stages[6:]}) == 1
assert [s['compilations'] for s in pressure_stages] == [1]*6 + [2]*6
for i, s in enumerate(pressure_stages):
    n = s['side']
    assert s['configured'] == 64 and not s['pending'] and s['reserved'] == 0
    assert 0 <= s['cacheBytes'] <= s['cacheLimit'] <= 64*1024*1024
    if s['level'] >= 3:
        assert s['cacheBytes'] == s['cacheLimit'] == 0
    value = 1 if i < 6 else 0
    assert s['value'] == value
    raw = (vp_root / (s['name'] + '.bin')).read_bytes()
    assert len(raw) == 4*n**3
    assert s['encoding'] == {'offset': value, 'scale': 1, 'min': value, 'max': value}
    assert raw == b'\0' * len(raw)  # Uniform canonical values encode as positive float zero.
    optical_density = 0 if s['name'] in ('deferred', 'paused-upload') else value
    t = math.exp(-optical_density*.01*n)
    expected = [127*(1-t)+255*t*.055, 255*t*.075, 255*t*.1, 255]
    assert all(abs(a-b) <= 2 for a,b in zip(s['pixel'], expected)), s
assert 'deferred' in pressure_stages[4]['message']
assert all('Resume' in pressure_stages[i]['message'] for i in (5,10))
assert 0 < vp['maximumUploadBytes'] <= 16384
assert all(vp[k] for k in ['initialViewOnly', 'postEditViewOnly', 'configuredPreserved',
                         'midUploadCanceled', 'queuedReservationReclaimed', 'explicitResume', 'deferredBudgetEscalated'])
queued = vp['queuedMemory']
assert queued['peak'] == 12*64**3 + 8*32**3 + 128 + 65536
assert queued['cpuBytes'] + queued['reserved'] >= queued['peak'] and queued['queueSize'] == 1
pressure_project = json.loads((vp_root / 'volume-pressure-project.json').read_text())
assert pressure_project['noise3D'][0]['processing'][0]['parameters']['expression'] == '0'
print('VOLUME_PRESSURE_PASS independent retained-source texels / reduced-extent optics / exact publication versions / ordered fallback / cache release / paused upload and queue / explicit Resume / gradual restoration / view-only')

from verify_surface_lod import verify as verify_surface_lod
verify_surface_lod(Path(sys.argv[1]) / 'surface-lod')

from verify_gpu_timing import verify as verify_gpu_timing
verify_gpu_timing(Path(sys.argv[1]) / 'gpu-timing')

from verify_shadows import verify as verify_shadows
verify_shadows(Path(sys.argv[1]) / "shadows")

from verify_volume_light import verify as verify_volume_light
verify_volume_light(Path(sys.argv[1]) / "volume-light")

# Deferred development extension; invoked only when owner-authorized validation resumes.
import subprocess
subprocess.run([sys.executable, str(Path(__file__).with_name("verify_property_focus.py")),
                str(Path(sys.argv[1]) / "property-focus")], check=True)
