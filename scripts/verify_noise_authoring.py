"""Independent metadata, bounded-native-row and authored arithmetic checks."""
import argparse,json
from pathlib import Path
import jsonschema
p=argparse.ArgumentParser();p.add_argument('project',type=Path);p.add_argument('report',type=Path);a=p.parse_args()
root=Path(__file__).resolve().parent.parent
registry=json.loads((root/'registries/noise-authoring.json').read_text())
assert registry['registryVersion']==1 and registry['projectFormatVersion']==2
assert registry['types']==['Scalar','Boolean','Vector2','Vector3','Coordinates2D','Coordinates3D']
assert registry['finalOutputType']=='Scalar' and registry['structuralEditsUseCompilerValidation']
assert registry['draftPublicationRequiresUnchangedBase']
project=json.loads(a.project.read_text(encoding='utf-8'));r=json.loads(a.report.read_text(encoding='utf-8'))
schema=json.loads((root/'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker()).validate(project)
assert r['formatVersion']==1 and r['items']==2002 and r['parametersBefore']==2000 and r['parametersAfter']==1999
assert 0<r['itemRows']<=30 and 0<=r['propertyRows']<=30 and 0<r['parameterRows']<=30
assert r['filteredRows']==1 and r['callbacks']==1 and r['metadataBuilds']>0 and r['sample']==12
assert r['focusFailureConsumed'] is True and r['focusFailureCallbacks']==1
n=project['noise2D'][0]
assert n['id']=='authoring' and n['algorithm']['key']=='CONSTANT' and n['algorithm']['parameters']['value']==1
assert len(n['processing'])==2002 and len(n['sharedParameters'])==1999
assert all(not item['enabled'] for item in n['processing'][:2000])
add,multiply=n['processing'][-2:]
assert add['kind']=='ADD' and multiply['kind']=='MULTIPLY'
assert add['inputs']['a']['sourceKind']=='Source Result'
assert add['inputs']['b']['sourceKind']=='Constant' and add['inputs']['b']['value']==2
assert add['inputs']['b'].get('parameterUuid') is None
assert multiply['inputs']['a']['itemUuid']==add['uuid'] and multiply['inputs']['b']['value']==4
assert n['outputBinding']['itemUuid']==multiply['uuid']
assert n['pinnedControls']==[{'targetUuid':add['uuid'],'control':'','label':'Add settings','group':'Common'}]
assert (n['algorithm']['parameters']['value']+add['inputs']['b']['value'])*multiply['inputs']['b']['value']==12
assert project['worldGenerator']['layers']==[]
print('Noise authoring schema, six types, bounded native widgets, canonical pin cleanup, disabled preservation and independent (1+2)*4 expectation PASS')

# Deferred background structural controls and full saved-state replay.
background = a.project.parent / 'background'
receipt = json.loads((background / 'noise-edit-native.json').read_text())
assert receipt['formatVersion'] == 1 and receipt['physicalInputVerified'] is False
modes = {'automatic', 'resolve', 'cancel', 'escape', 'retry', 'stale', 'close', 'ready-cancel', 'ready-escape', 'ready-close', 'ready-invalid-cancel', 'ready-resolve-cancel'}
assert len(receipt['cases']) == len(modes) and {case['mode'] for case in receipt['cases']} == modes
for case in receipt['cases']:
    mode = case['mode']; staged = mode in {'automatic', 'resolve', 'retry'}
    assert case['staged'] is staged
    assert case['readyRetired'] is mode.startswith('ready-')
    assert case['localChoiceHistory'] is (mode == 'resolve')
    assert case['queuedFrames'] >= 12 and case['heldFrames'] >= 12
    assert all(case[key] is True for key in ['privateRetained', 'rawRetained', 'preferencesRetained', 'originalBytesRetained'])
    before = json.loads((background / mode / 'before.json').read_text())
    after = json.loads((background / mode / 'after.json').read_text())
    for saved in [before, after]: jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(saved)
    expected = before
    noise, = expected['noise2D']
    assert noise['id'] == 'field' and len(noise['sharedParameters']) == 2
    if staged:
        noise['id'] = 'private'
        noise['sharedParameters'].pop(0 if mode == 'resolve' else 1)
        if mode == 'resolve':
            binding = noise['processing'][0]['inputs']['b']
            binding['sourceKind'] = 'Constant'; binding['value'] = 2.0
            for key in ['parameterUuid', 'itemUuid', 'noiseGeneratorUuid', 'ambientKey']: binding.pop(key, None)
    assert expected == after
assert json.loads((background / 'engine-replay.json').read_text()) == dict(formatVersion=1, fields=504, cells=7056, roots=[2, 4])
print('BACKGROUND_NOISE_EDIT_INDEPENDENT_PASS twelve structural lifetimes / exact saved edits / 504 fields / 7056 EMPTY cells')
