"""Independent expected scope, binding location and numerical fixture checks for native Find."""
from pathlib import Path
import argparse
import json
parser=argparse.ArgumentParser()
parser.add_argument('report',type=Path)
args=parser.parse_args()
r=json.loads(args.report.read_text(encoding='utf-8'))
assert r['formatVersion']==1 and r['resources']==1000
assert 0<r['topRows']<=40 and 0<r['scrollRows']<=40
assert r['builds']==1 and r['queriesBeforeScroll']==r['queriesAfterScroll']==1
assert r['filteredRows']==r['referenceCount']==1
assert r['breadcrumb']=='noise2D / continent / mountains / needle'
assert r['referencePointer']=='/noise2D/999/processing/0/inputs/linked'
assert r['curvePoints']==2 and r['selectedPoint']
assert r['sample']==.25+.5
print('Unified Find native bounds, cache reuse, exact hierarchy/reference context and independent arithmetic PASS')
