"""Validate organized model structure and independently check the explicit native hierarchy fixture."""
from pathlib import Path
import argparse
import json
import jsonschema
parser=argparse.ArgumentParser()
parser.add_argument('project',type=Path)
parser.add_argument('report',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
registry=json.loads((root/'registries/resource-hierarchy.json').read_text())
assert registry['registryVersion']==1 and registry['projectFormatVersion']==2
assert registry['protectedRoots']==['noise2D','noise3D','world']
assert registry['rootOrderPath']=='project.rootOrder' and registry['executionOrderPath']=='worldGenerator.layers'
assert registry['treeOrderAffectsExecution'] is False
p=json.loads(args.project.read_text(encoding='utf-8'))
jsonschema.Draft202012Validator(json.loads((root/'schema/project.schema.json').read_text()),format_checker=jsonschema.FormatChecker()).validate(p)
r=json.loads(args.report.read_text(encoding='utf-8'))
assert r['formatVersion']==1
assert r['execution']==['set','clear'] and r['stages']==[1,2]
assert r['worldRows']==['second','clear','everywhere','first','set','everywhere']
assert r['worldDepths']==[0,1,2,0,1,2]
assert r['noiseRoot']==['constant','fields'] and r['field']==1
assert 0<r['submittedRows']<=30
layers=p['worldGenerator']['layers']
folders={f['id']:f for f in p['folders']}
assert [l['parentFolderUuid'] for l in layers]==[folders['first']['uuid'],folders['second']['uuid']]
assert p['project']['rootOrder']['world']==[folders['second']['uuid'],folders['first']['uuid']]
assert p['project']['rootOrder']['noise3D']==[p['noise3D'][0]['uuid'],folders['fields']['uuid']]
assert all(not op['overrides'] for layer in layers for op in layer['operations'])
print('Hierarchy schema, exact mixed ordering, parent scopes, execution stages and absent overrides PASS')
