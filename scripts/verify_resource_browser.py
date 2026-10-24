"""Validate the isolated native browser's independent fixture and bounded-scroll expectations."""
from pathlib import Path
import argparse
import json

parser = argparse.ArgumentParser()
parser.add_argument('report', type=Path)
args = parser.parse_args()
report = json.loads(args.report.read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
assert report['noiseCount'] == 2000 and report['voxelCount'] == 5000
assert report['indexCount'] == 2000 + 5000 + 2 + 50 + 50
top, scrolled, filtered = (report[key] for key in ('top', 'scrolled', 'filtered'))
for stage in (top, scrolled, filtered):
    assert stage['indexed'] == report['indexCount']
    assert stage['builds'] == 1
    assert 0 < stage['treeRows'] <= 50
    assert 0 < stage['managerRows'] <= 50
    assert len(set(stage['submitted'])) == len(stage['submitted']) == stage['treeRows']
assert top['submitted'] != scrolled['submitted']
assert top['projections'] == scrolled['projections']
assert filtered['treeRows'] == 3 and filtered['managerRows'] == 1
assert report['filteredLabels'] == ['outer', 'inner', 'noise-01999']
assert report['filteredDepths'] == [0, 1, 2]
assert report['filteredTypeIds'] == ['type-04999']
assert report['unchangedSample'] == 1999 / 10
print('Resource browser native fixture, bounded rows, cached scroll, ancestor/filter membership and independent constant PASS')
