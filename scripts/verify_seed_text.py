"""Deferred independent unsigned-64 reference for the native seed editor's saved project."""
from pathlib import Path
import csv
import json
import struct
import sys
import uuid
import jsonschema

out = Path(sys.argv[1])
repo = Path(__file__).resolve().parent.parent
project = json.loads((out / 'seed-text-project.json').read_text())
native = json.loads((out / 'seed-text-native.json').read_text())
engine = json.loads((out / 'seed-text-engine.json').read_text())
schema = json.loads((repo / 'schema/project.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(project)
noise, = project['noise2D']
layer, = project['worldGenerator']['layers']
operation, = layer['operations']
assert noise['seedOffset'] == 17 and layer['defaults']['seedOffset'] == -(1 << 63)
assert layer['defaults']['fieldGates']['seedOffset'] == (1 << 63) - 1
assert operation['overrides'] == {'seedOffset': 43}
assert layer['defaults']['fieldGates']['kind'] == 'PROBABILITY' and layer['defaults']['fieldGates']['probability'] == 1
assert layer['defaults']['action'] == 'SET' and operation['interpretation'] == 'ALL'
for report in [native, engine]:
    assert report['formatVersion'] == 1
    assert report['sourceSeed'] == 17 and report['layerSeed'] == -(1 << 63)
    assert report['gateSeed'] == (1 << 63) - 1 and report['overrideSeed'] == 43
assert all(native[key] is True for key in ['rawTextRetained', 'guardedApplyRejected', 'restoreKeptOverride', 'privateDrafts', 'oneUndoRedo'])
assert engine['hashes'] == 81 and engine['cells'] == 144
mask = (1 << 64) - 1

def mix(value):
    value &= mask
    value = ((value ^ (value >> 30)) * 0xbf58476d1ce4e5b9) & mask
    value = ((value ^ (value >> 27)) * 0x94d049bb133111eb) & mask
    return value ^ (value >> 31)

def rotate(value, bits):
    return ((value << bits) | (value >> (64 - bits))) & mask

identity = uuid.UUID(noise['uuid']).int
seed = mix(project['project']['seed'] ^ mix(identity >> 64) ^ rotate(mix(identity & mask), 17) ^ mix(noise['seedOffset']))
with (out / 'seed-text-values.tsv').open(newline='') as source:
    rows = list(csv.DictReader(source, delimiter='\t'))
assert len(rows) == 81 and {(int(r['x']), int(r['z'])) for r in rows} == {(x, z) for x in range(-4, 5) for z in range(-4, 5)}
for row in rows:
    x, z = int(row['x']), int(row['z'])
    hashed = mix(seed ^ mix(x * 0x9e3779b97f4a7c15) ^ rotate(mix(z * 0x165667b19e3779f9), 42))
    expected = (hashed >> 11) * 2**-53
    bits, = struct.unpack('>Q', struct.pack('>d', expected))
    assert float(row['value']) == expected and int(row['bits'], 16) == bits
print('SEED_TEXT_INDEPENDENT_PASS exact signed-long persistence / 81 independent SplitMix raw-bit vectors / probability-one gate')
