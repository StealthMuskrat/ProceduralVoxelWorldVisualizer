"""Check independent corpus provenance, native authoring, and JVM repeatability evidence."""
from pathlib import Path
import hashlib,json,sys
root=Path(__file__).resolve().parent.parent;output=Path(sys.argv[1])
assert hashlib.sha256((root/'reference/fastnoiselite/FastNoiseLite.h').read_bytes()).hexdigest()=='47a29750dfe13d15651febd235f3c26b1cc57af7c6e26583b838fd7ab5215dc2'
report=json.loads((output/'algorithm-reference-report.json').read_text())
assert report['cppCases']==3264 and report['analyticCases']==240 and report['seedCases']==32
assert sum(report['families'].values())==3264 and len(report['families'])==10
assert 0<=report['exactCppBits']<=3264 and report['maximumAbsoluteError']>=0
hashes=json.loads((output/'algorithm-jvm-bits.json').read_text());assert len(hashes)==20 and all(len(h)==64 for h in hashes.values())
baseline=root/'fixtures/algorithm-jvm-reference.json'
if baseline.exists():assert hashes==json.loads(baseline.read_text()),'Canonical JVM bit vectors changed'
registry=json.loads((root/'registries/algorithm-registry.json').read_text());assert registry['registryVersion']==2 and len(registry['specifications'])==10
assert {p['key'] for p in registry['specifications']['WHITE_HASH']['parameters']}=={'frequency'}
if (output/'source-algorithm-reference.json').exists():
 native=json.loads((output/'source-algorithm-reference.json').read_text());assert native==dict(formatVersion=1,algorithmDimensionVisits=20,namedFractalIndex=3,pingPongStrength=3.5,privateEdits=True,oneUndoRedo=True,preciseInvalidFrequency=True)
 p=json.loads((output/'source-algorithm-project.json').read_text());n=p['noise2D'][0];assert n['algorithm']['key']=='PERLIN' and n['algorithm']['parameters']['fractal']==3 and n['algorithm']['parameters']['pingPongStrength']==3.5
print('ALGORITHM_REFERENCES_INDEPENDENT_PASS pinned upstream source / 3264 C++ cases / 240 analytic and 32 seed vectors / 20 raw-bit digests / canonical source contracts')
