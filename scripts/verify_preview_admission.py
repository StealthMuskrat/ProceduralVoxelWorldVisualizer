import json,sys
from pathlib import Path
r=json.loads((Path(sys.argv[1])/'preview-admission-native.json').read_text())
assert r['formatVersion']==7 and r['workers']==2 and r['queueCapacity']==8
assert 'configured budget' in r['budgetMessage'] and 'queue is full' in r['queueMessage']
assert r['raw']==.75
assert all(r[k] is True for k in ['retainedImage','contextRetry','toolbarRetry','otherViewsUnblocked','viewOnly'])
print('PREVIEW_ADMISSION_PYTHON_PASS native retry report / exact constant output')

from uuid import UUID
UUID(r['generatorUuid'])
assert 0<r['unfilteredRows']<r['generatorCount']==64
assert r['filteredRows']==1 and r['dimensionScoped'] is True and r['pickerEscape'] is True
print('VIEWPORT_GENERATOR_PYTHON_PASS clipped / filtered / dimension-scoped UUID / Escape')

UUID(r['spatialUuid'])
assert r['spatialRows']==2
assert all(r[k] is True for k in ['independentFilters','clearReselect','missingNoWork','deleteUndo'])
print('VIEWPORT_SELECTION_RECOVERY_PASS 3D / independent filters / clear / missing / Undo')

assert r['streamGenerated']>0
assert all(r[k] is True for k in ['streamClearReleased','streamDeleteReleased','streamReselect','streamUndoRestored'])
print('VIEWPORT_STREAM_RECOVERY_PASS generated / clear-delete CPU-GPU release / reselect / Undo')

assert r['retainedStreamEpoch']>0
assert all(r[k] is True for k in ['sourceFailureRetained','sourceFailureNoRetry','sourceRetryRecovered'])
print('STREAM_SOURCE_FAILURE_PASS retained epoch / no fixed fallback / explicit retry')

assert r['startupFallbackStamp']>0 and r['frameFallbackStamp']>r['startupFallbackStamp']
assert all(r[k] is True for k in ['rendererStartupFallback','rendererFrameFallback','rendererResourcesReleased','rendererRetryRecovered','fixedFallbackStable'])
print('STREAM_RENDERER_FALLBACK_PASS startup-frame cleanup / fixed publication / explicit recovery')

import hashlib
for key in ['visibleBefore','visibleOrbit','visibleZoom','visiblePan']:
 o=r[key];data=(Path(sys.argv[1])/o['rgbaFile']).read_bytes()
 assert len(data)==o['width']*o['height']*4
 assert hashlib.sha256(data).hexdigest()==o['sha256']
 ink=sum(max(abs(data[i]-14),abs(data[i+1]-19),abs(data[i+2]-26))>6 for i in range(0,len(data),4))
 assert ink==o['inkPixels'] and ink>100
assert r['visibleZoom']['inkPixels']>r['visibleOrbit']['inkPixels']
assert len({r[k]['sha256'] for k in ['visibleBefore','visibleOrbit','visibleZoom','visiblePan']})==4
assert all(r[k] is True for k in ['orbitZoomNoGeneration','panRebuilt','cameraViewOnly'])
print('VISIBLE_FALLBACK_CAMERA_PASS independent RGBA digests / visible pixels / native orbit-zoom-pan')
