"""Independent fixture checks for the native picker/membership/export workflow."""
from pathlib import Path
import argparse
import json

parser = argparse.ArgumentParser()
parser.add_argument('report', type=Path)
parser.add_argument('package', type=Path)
args = parser.parse_args()
report = json.loads(args.report.read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['catalogTypes'] == 2000
assert report['pickerRows'] == report['pickerCallbacks'] == report['membershipRows'] == 1
assert 0 < report['exportRows'] <= 30
assert 0 < report['unfilteredPickerRows'] <= 30
assert 0 < report['unfilteredMembershipRows'] <= 30
assert report['filteredExportRows'] == report['preparations'] == 1
assert report['activeId'] == 'catalog' and report['draftId'] == 'pending-id'
assert report['exportedTypeIds'] == ['type-00000', 'type-01999'] and report['exportedMembers'] == 2
pack = json.loads(args.package.read_text(encoding='utf-8'))
assert pack['packageVersion'] == 1
resources = pack['resources']
types = resources['voxelTypes']
assert [t['id'] for t in types] == report['exportedTypeIds']
assert resources['voxelSets'][0]['voxelTypeUuids'] == [t['uuid'] for t in types]
assert resources['noise2D'][0]['algorithm']['parameters']['value'] == .625
assert not resources['noise3D'] and not resources['worldGenerator']['layers']
print('Resource workflow native counts, exact package membership, draft-ID separation and independent constant PASS')
