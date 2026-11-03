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
assert all(report[key] is True for key in ['activeProjectProtected', 'packageRoundTrip', 'packageDefaultDistinct'])
assert all(report[key] is True for key in ['exportStaleRejected', 'exportReloadReviewed', 'exportConsumed', 'exportPrivateDraftPreserved'])
assert report['preparationsAfterReload'] == 2
project = json.loads((args.package.parent / 'active-project.pvproj.json').read_text(encoding='utf-8'))
assert project['formatVersion'] == 2 and 'packageVersion' not in project
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
lifetime_root = args.report.parent / 'pane-lifetime'
lifetime = json.loads((lifetime_root / 'resource-pane-lifetime.json').read_text(encoding='utf-8'))
assert lifetime['formatVersion'] == 1
lifetime_cases = {row['mode']: row for row in lifetime['cases']}
assert len(lifetime_cases) == len(lifetime['cases']) == 6
assert set(lifetime_cases) == {'COPY', 'CUT', 'RENAME', 'PENDING', 'PREPARED', 'STABLE'}
for mode, row in lifetime_cases.items():
    stable = mode == 'STABLE'
    assert row['privatePreserved'] and row['preferencesPreserved']
    assert row['clipboard'] == stable
    if stable:
        assert row['newOpening'] == row['oldOpening']
    else:
        assert row['newOpening'] > row['oldOpening']
    if mode == 'RENAME':
        assert row['oldRenameId'] != 0 and row['newRenameId'] != 0
        assert row['oldRenameId'] != row['newRenameId']
    elif stable:
        assert row['oldRenameId'] == row['newRenameId'] != 0
    active = json.loads((lifetime_root / f'{mode}-active.json').read_text(encoding='utf-8'))
    source = json.loads((lifetime_root / f'{mode}-source.json').read_text(encoding='utf-8'))
    assert active == source
    assert active['project']['seed'] == row['seed'] == (42 if stable else 41)
    assert len(active['noise2D']) == 1 and not active['noise3D'] and not active['worldGenerator']['layers']
    assert active['noise2D'][0]['id'] == 'field'
    assert active['noise2D'][0]['algorithm']['parameters'] == {'value': .25}
print('Resource workflow native counts, exact package membership, draft-ID separation and independent constant PASS')


from verify_resource_package_schema import validator as package_validator
package_validator(Path(__file__).resolve().parent.parent).validate(pack)
