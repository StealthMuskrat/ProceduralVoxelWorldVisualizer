"""Deferred independent dock/chrome partition and explicit action receipts."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'status-bar-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for flag in ('projectPreserved', 'oldAndNewIniRestored', 'unreadPreserved'):
    assert report[flag] is True
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']
assert report['settingsCalls'] == report['notificationCalls'] == 4
assert 0 < int(report['legacyDockId']) <= 0xffffffff
expected = {'wide-1': (1280, 720, 1, False), 'narrow-2.5': (360, 240, 2.5, True),
            'small-0.5': (480, 320, .5, False), 'restart-1': (800, 600, 1, False)}
assert len(report['cases']) == len(expected)
assert {case['name'] for case in report['cases']} == set(expected)
for case in report['cases']:
    width, height, scale, two_rows = expected[case['name']]
    assert (case['displayWidth'], case['displayHeight'], case['scale']) == (width, height, scale)
    plan = case['plan']
    assert plan['twoRows'] == two_rows
    assert case['actual'] == plan['bar']
    dock, bar = plan['dock'], plan['bar']
    assert dock['x'] == bar['x'] == 0 and dock['width'] == bar['width'] == width
    assert dock['y'] > 0 and dock['y'] + dock['height'] == bar['y']
    assert bar['y'] + bar['height'] == height
    for action in ('settings', 'notification'):
        rect = case[action]
        assert all(math.isfinite(v) for v in rect.values())
        assert rect['width'] > 0 and rect['height'] > 0
        assert bar['x'] <= rect['x'] and bar['y'] <= rect['y']
        assert rect['x'] + rect['width'] <= bar['x'] + bar['width'] + .02
        assert rect['y'] + rect['height'] <= bar['y'] + bar['height'] + .02
    assert case['notification']['x'] + case['notification']['width'] == width - 8
print('Status dock partition, scaled action bounds, old/new INI and explicit notification receipts PASS')
