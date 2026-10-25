"""Independent arithmetic, provenance and native retention checks, without editor imports."""
from pathlib import Path
import json,sys
root=Path(sys.argv[1]);p=json.loads((root/'trace-project.json').read_text());t=json.loads((root/'trace-result.json').read_text());r=json.loads((root/'trace-workflow-reference.json').read_text())
assert r['syntheticSteps']==2000 and 0<r['stepRows']<=30 and 0<r['valueRows']<=30 and 0<r['logRows']<=30 and r['filteredRows']==1 and r['guardedNavigation']
assert r['primaryField']==(2+2*(2+1)+3)*2==22 and t['finalType']==1
assert len(t['steps'])==2
layer=p['worldGenerator']['layers'][0]
a,b=t['steps'];assert a['action']=='SET' and b['action']=='NO_CHANGE' and a['result']==b['result']==1
for step,threshold,owner,passed in [(a,10,layer['uuid'],True),(b,30,layer['operations'][1]['uuid'],False)]:
 d=step['details'];c=d['comparisons'][-1];assert c==dict(property='threshold',left=22.0,operator='>=',right=float(threshold),passed=passed)
 assert d['settingTargets']['threshold']['resourceUuid']==owner and d['primaryField']['value']==22
 assert [e['phase'] for e in d['primaryField']['events']]==['TRANSFORM','SOURCE','PROCESSING','PROCESSING','OUTPUT']
 assert d['primaryField']['events'][0]['values']==[3.0,3.0]
assert b['details']['gatePassed'] is None and b['details']['predicatePassed'] is None
assert 'Compare threshold: 22.0 >= 30.0 : rejected' in (root/'trace-details.txt').read_text()
print('TRACE_WORKFLOW_INDEPENDENT_PASS exact arithmetic / transforms / typed observations / live-override UUID provenance / first rejection / bounded native rows and guarded edits')
