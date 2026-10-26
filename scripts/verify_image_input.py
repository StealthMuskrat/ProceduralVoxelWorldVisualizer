"""Independent geometry from the production native event observations."""
import json, math, sys
from pathlib import Path
r=json.loads((Path(sys.argv[1])/'image-input-native.json').read_text())
a,b=r['before'],r['zoomed']
assert math.isclose(b['step'],a['step']*math.exp(-.3),rel_tol=0,abs_tol=1e-12)
for axis,t in [('x',r['u']),('z',r['v'])]:
 assert math.isclose(a[axis]+(t-.5)*256*a['step'],b[axis]+(t-.5)*256*b['step'],rel_tol=0,abs_tol=1e-10)
a,b=r['panBefore'],r['panned']
assert math.isclose(b['x'],a['x']-32/640*256*a['step'],rel_tol=0,abs_tol=1e-10)
assert math.isclose(b['z'],a['z']+18/360*256*a['step'],rel_tol=0,abs_tol=1e-10)
assert a['step']==b['step']
assert all(r[k] is True for k in ['released','outsideIgnored','saturatedNoWork','viewOnly'])
print('IMAGE_INPUT_PYTHON_PASS native cursor anchor / pan arithmetic / lifecycle')

assert r['formatVersion']==3
a,b=r['captureBefore'],r['outsidePan']
assert math.isclose(b['x'],a['x']-r['outsideDx']/640*256*a['step'],rel_tol=0,abs_tol=1e-10)
assert math.isclose(b['z'],a['z']-r['outsideDy']/360*256*a['step'],rel_tol=0,abs_tol=1e-10)
assert a['step']==b['step']
assert all(r[k] is True for k in ['foreignDragIgnored','escapeRestored','heldWheelIgnored','freshDragAfterCancel'])
print('IMAGE_CAPTURE_PYTHON_PASS outside drag / ownership / Escape')

assert r['popupBefore']==r['popupAfter']
assert r['focusBefore']==r['focusAfter']
assert all(r[k] is True for k in ['popupBlocked','focusBlocked','guardRecovery'])
print('IMAGE_GUARD_PYTHON_PASS popup / window focus / held-button recovery')
