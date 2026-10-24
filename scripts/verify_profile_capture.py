"""Validate exported JSON/CSV, including independent series/percentile expectations."""
import argparse
import csv
import json
import math
from collections import defaultdict
from pathlib import Path
import jsonschema

parser = argparse.ArgumentParser()
parser.add_argument('json_capture', type=Path)
parser.add_argument('csv_capture', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
capture = json.loads(args.json_capture.read_text(encoding='utf-8'))
schema = json.loads((root / 'schema/profile-capture.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(capture)
assert len(capture['samples']) <= capture['options']['maxSamples']
assert len(capture['series']) <= capture['options']['maxSeries']
assert capture['elapsedNanos'] <= capture['options']['durationMillis'] * 1_000_000
identity = ('kind', 'unit', 'contextId', 'projectUuid', 'sourceUuid', 'label')
groups = defaultdict(list)
for sample in capture['samples']:
    groups[tuple(sample[k] for k in identity)].append(sample)
assert len(groups) == len(capture['series'])
seen = set()
for series in capture['series']:
    key = tuple(series[k] for k in identity)
    assert key not in seen
    seen.add(key)
    samples = groups[key]
    values = sorted(s['value'] for s in samples)
    assert series['count'] == len(values)
    assert series['minimum'] == values[0] and series['maximum'] == values[-1]
    assert math.isclose(series['mean'], math.fsum(values) / len(values), rel_tol=1e-12, abs_tol=1e-9)
    for name, p in [('p50', .5), ('p95', .95), ('p99', .99)]:
        assert series[name] == values[math.ceil(p * len(values)) - 1]
    for column, outcome in [('failed', 'FAILED'), ('cancelled', 'CANCELLED')]:
        assert series[column] == sum(s['outcome'] == outcome for s in samples)
with args.csv_capture.open(encoding='utf-8', newline='') as source:
    rows = list(csv.DictReader(source))
assert len(rows) == len(capture['samples'])
for row, sample in zip(rows, capture['samples']):
    expected = dict(capture_id=capture['captureId'], state=capture['state'], started_at=capture['startedAt'],
                    context_id=sample['contextId'], project_uuid=sample['projectUuid'], kind=sample['kind'],
                    source_uuid=sample['sourceUuid'], label=sample['label'], offset_ns=sample['offsetNanos'],
                    value=sample['value'], unit=sample['unit'], outcome=sample['outcome'],
                    thread_id=sample['threadId'], thread_name=sample['threadName'])
    for axis in ('x', 'y', 'z'):
        expected['chunk_' + axis] = None if sample['chunk'] is None else sample['chunk'][axis]
    assert row == {k: '' if v is None else str(v) for k, v in expected.items()}
print(f"Profile JSON/CSV, bounds, summaries and nearest-rank percentiles PASS: {len(rows)} samples / {len(groups)} series")
