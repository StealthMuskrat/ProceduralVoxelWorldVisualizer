"""Validate captured GPU durations against their unsigned driver timestamp receipts."""
import json
from pathlib import Path
import subprocess
import sys


def verify(root):
    root = Path(root)
    data = json.loads((root / 'gpu-timing-native.json').read_text())
    assert data['formatVersion'] == 1 and data['glVersion']
    strict = root.name.endswith('gl30')
    if strict:
        assert not data['openGL31']
    supported = data['supported']
    assert supported == (30 <= data['counterBits'] <= 64)
    repository = Path(__file__).resolve().parent.parent
    captures = {}
    for name in ('gpu-capture', 'replacement-capture') + (() if strict else ('ui-capture',)):
        subprocess.run([sys.executable, str(repository / 'scripts/verify_profile_capture.py'),
                        str(root / (name + '.json')), str(root / (name + '.csv'))], check=True)
        captures[name] = json.loads((root / (name + '.json')).read_text())
    def receipts(name, rows, labels):
        capture = captures[name]
        samples = capture['samples']
        assert len(rows) == len(samples) == (len(labels) if supported else 0)
        if supported:
            assert [r['label'] for r in rows] == labels
        for row, sample in zip(rows, samples):
            bits = row['counterBits']
            start, end = int(row['startHex'], 16), int(row['endHex'], 16)
            assert 0 <= start < 2**64 and 0 <= end < 2**64
            assert bits == data['counterBits']
            elapsed = (end-start) % (1 << bits)
            assert row['elapsedNanos'] == sample['value'] == elapsed
            assert 0 <= elapsed < min(2_000_000_000, 1 << bits)
            assert row['captureId'] == capture['captureId'] and row['recorded']
            assert row['offsetNanos'] == sample['offsetNanos'] <= capture['elapsedNanos']
            assert sample['kind'] == 'GPU_DRAW' and sample['unit'] == 'NANOSECONDS'
            assert sample['outcome'] == 'MEASURED' and sample['sourceUuid'] is None
            assert sample['contextId'] == 0 and sample['projectUuid'] == '00000000-0000-0000-0000-00000000004c'
            assert sample['label'] == row['label']
    receipts('gpu-capture', data['receipts'], ['fixed-mesh', 'volume', 'streamed-scene'])
    receipts('replacement-capture', data['replacementReceipts'], ['replacement'])
    if supported:
        assert any(r['elapsedNanos'] > 0 for r in data['receipts'])
    stats = data['stoppedStatistics']
    assert stats['allocatedQueries'] == stats['pending'] == 0
    assert stats['recorded'] == (3 if supported else 0)
    assert [f['name'] for f in data['frames']] == ['fixed-mesh','volume','streamed-scene']
    assert all(len(f['framebufferHash']) == 64 and f['pixel'][3] == 255 for f in data['frames'])
    assert all(data[k] for k in ('captureReplaced','stopDiscarded','driverNamesDeleted'))
    if not strict:
        gpu = [s for s in captures['ui-capture']['samples'] if s['kind'] == 'GPU_DRAW']
        assert len(gpu) == data['productionGpuSamples']
        assert bool(gpu) == supported
        assert all(s['unit'] == 'NANOSECONDS' and s['outcome'] == 'MEASURED'
                   and s['label'] == '3D Noise viewport GPU commands' for s in gpu)
        assert all(s['pending'] == s['allocatedQueries'] == 0 for s in data['productionStatistics'])
        assert data['profileUiDrawn'] and data['viewOnly']
        project = json.loads((root / 'gpu-timing-project.json').read_text())
        assert project['noise3D'][0]['processing'][0]['parameters']['expression'] == '1'
    print('GPU_TIMING_PASS independent unsigned timestamp deltas / issue-stamp capture identity / JSON-CSV statistics'
          ' / replacement-stop cleanup / optional capability / unchanged pixels and production volume; ' + data['glVersion'])


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
