"""Deferred native cursor-lease report contract; no physical/detached-layout certification."""
import json
import sys
from pathlib import Path


def verify(root):
    report = json.loads((Path(root) / 'cursor-lease-native.json').read_text(encoding='utf-8'))
    assert report['formatVersion'] == 1
    for key in ('secondaryCapture', 'mainUnchanged', 'focusLossRelease', 'hiddenModeRestored',
                'mainCaptureTransfer', 'destroyedWindowSkipped', 'backendPolicyRestored'):
        assert report[key] is True, key
    print('Native cursor lease report PASS')


if __name__ == '__main__':
    verify(Path(sys.argv[1]))
