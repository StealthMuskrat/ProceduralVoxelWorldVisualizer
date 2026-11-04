"""Deferred independent Fraction identities, saved curve values, schemas and replay dimensions."""
from fractions import Fraction as F
from pathlib import Path
import json
import math
import struct
import sys
import jsonschema

repo = Path(__file__).resolve().parent.parent
root = Path(sys.argv[1])
vectors = json.loads((repo / 'fixtures/curve-linear-vectors.json').read_text())
registry = json.loads((repo / 'registries/curve-modes.json').read_text())
assert vectors['formatVersion'] == 1 and len(vectors['cases']) == 17
assert vectors['arithmeticPolicy'] == registry['linearArithmeticPolicy'] == 'guarded-linear-and-extension-v1'
assert registry['arithmeticPolicy'] == 'guarded-harmonic-and-coefficients-v1'
schema = json.loads((repo / 'schema/project.schema.json').read_text())
validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())


def bits(value):
    return struct.pack('>d', float(value))


def number(row, key):
    return float.fromhex(row[key + 'Hex'])


def exact(row, key):
    return F.from_float(number(row, key))


def reference(row, sample):
    lo, hi, a, b = (exact(row, key) for key in ['min', 'max', 'y0', 'y1'])
    value = exact(sample, 'x')
    if value == lo:
        return number(row, 'y0')
    if value == hi:
        return number(row, 'y1')
    mode, outside = row['interpolation'], row['outside']
    if value < lo or value > hi:
        if outside == 'CLAMP':
            return number(row, 'y0' if value < lo else 'y1')
        if outside == 'EXTEND':
            if mode == 'LINEAR':
                result = a + (value - lo) * (b - a) / (hi - lo)
            else:
                left = value < lo
                slope = F(0) if mode == 'STEP' else exact(row, 'outTangent' if left else 'inTangent')
                result = (a if left else b) + (value - (lo if left else hi)) * slope
            return float(result)
        length = hi - lo
        if outside == 'REPEAT':
            value = lo + (value - lo) % length
        else:
            assert outside == 'MIRROR'
            phase = (value - lo) % (2 * length)
            value = lo + min(phase, 2 * length - phase)
    if value == lo:
        return number(row, 'y0')
    if value == hi:
        return number(row, 'y1')
    t = (value - lo) / (hi - lo)
    if mode == 'STEP':
        return number(row, 'y0')
    if mode == 'LINEAR':
        result = a + t * (b - a)
    else:
        assert mode == 'BEZIER'
        # Independent Hermite basis, not the implementation's factored coefficient/Horner form.
        m0, m1 = exact(row, 'outTangent') * (hi - lo), exact(row, 'inTangent') * (hi - lo)
        result = (2*t**3 - 3*t**2 + 1)*a + (t**3 - 2*t**2 + t)*m0 \
            + (-2*t**3 + 3*t**2)*b + (t**3 - t**2)*m1
    return float(result)


def check_curve(project, row, dimension):
    validator.validate(project)
    noises = project['noise2D' if dimension == 2 else 'noise3D']
    assert len(noises) == 1
    noise = noises[0]
    assert noise['id'] == 'curve' and noise['dimension'] == dimension
    assert noise['algorithm']['key'] == 'CONSTANT' and noise['algorithm']['parameters'] == {'value': 0}
    assert len(noise['processing']) == 1
    item = noise['processing'][0]
    assert item['kind'] == 'CURVE'
    assert item['inputs']['a']['sourceKind'] == 'Ambient Value' and item['inputs']['a']['ambientKey'] == 'x'
    curve = item['parameters']['curve']
    assert bits(curve['min']) == bits(number(row, 'min')) and bits(curve['max']) == bits(number(row, 'max'))
    assert curve['outsideDomain'] == row['outside'] and curve['displayMin'] == -.2 and curve['displayMax'] == 1.2
    first, last = curve['points']
    assert bits(first['x']) == bits(number(row, 'min')) and bits(last['x']) == bits(number(row, 'max'))
    assert bits(first['y']) == bits(number(row, 'y0')) and bits(last['y']) == bits(number(row, 'y1'))
    assert first['segmentInterpolation'] == row['interpolation']
    assert first['handleMode'] == last['handleMode'] == ('FREE' if row['interpolation'] == 'BEZIER' else 'AUTO')
    assert bits(first['outTangent']) == bits(number(row, 'outTangent'))
    assert bits(last['inTangent']) == bits(number(row, 'inTangent'))
    assert noise['outputBinding']['sourceKind'] == 'Earlier Result' and noise['outputBinding']['itemUuid'] == item['uuid']


projects = samples = 0
for row in vectors['cases']:
    for sample in row['samples']:
        expected = number(sample, 'expected')
        actual = reference(row, sample)
        assert math.isfinite(actual) and bits(actual) == bits(expected), (row['id'], sample, actual.hex())
        samples += 1
    for dimension in [2, 3]:
        project = json.loads((root / f"{row['id']}-{dimension}.pvproj.json").read_text())
        check_curve(project, row, dimension)
        projects += 1
assert projects == 34 and samples == 75
world = json.loads((root / 'curve-threshold-world.pvproj.json').read_text())
check_curve(world, vectors['cases'][0], 2)
assert world['project']['minY'] == -1 and world['project']['maxY'] == 1
assert len(world['voxelTypes']) == 1 and world['voxelTypes'][0]['id'] == 'curved'
layer, = world['worldGenerator']['layers']
assert layer['defaults']['action'] == 'SET' and layer['defaults']['threshold'] == 0
assert layer['defaults']['voxelTypeUuid'] == world['voxelTypes'][0]['uuid']
operation, = layer['operations']
assert operation['noiseGeneratorUuid'] == world['noise2D'][0]['uuid'] and operation['interpretation'] == 'SELECTION'
report = json.loads((root / 'curve-linear-engine.json').read_text())
assert report == dict(formatVersion=1, arithmeticPolicy=vectors['arithmeticPolicy'], cases=17,
    projects=34, samples=900, worldCells=300, samplingPaths=['DIRECT', 'GRID', 'POINTS'],
    rawBits=True, projectDataUnchanged=True)
print('CURVE_LINEAR_INDEPENDENT_PASS 75 exact Fraction identities / 35 saved schemas / 900 fields / 300 World cells')
