"""Deferred independent native popup/client/monitor intersection receipts; synthetic inventory."""
import json
import math
from pathlib import Path
import sys

report = json.loads((Path(sys.argv[1]) / 'popup-monitor-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1 and report['inventorySource'] == 'synthetic'
for flag in ('privatePathPreserved', 'queryPreserved', 'cancelAndEscapePreserved', 'projectPreserved'):
    assert report[flag] is True
assert not report['physicalDetachedVerified'] and not report['physicalDpiVerified']
expected = {'modal-left': (0, 0, 480, 680, True), 'modal-right': (480, 0, 480, 680, True),
            'modal-inset': (480, 40, 360, 480, True), 'selector-right': (480, 40, 360, 480, True),
            'selector-left': (0, 20, 400, 500, True), 'selector-empty': (0, 0, 960, 720, False),
            'selector-relative': (0, 0, 960, 720, False)}
assert len(report['cases']) == len(expected)
assert {case['name'] for case in report['cases']} == set(expected)
for case in report['cases']:
    x, y, width, height, constrained = expected[case['name']]
    assert case['client'] == dict(x=0, y=0, width=960, height=720)
    work, bounds = case['work']['bounds'], case['bounds']
    assert work == dict(x=x, y=y, width=width, height=height)
    assert case['work']['monitorConstrained'] == constrained
    assert not case['work']['recoveredToMain']  # This native fixture has one real ImGui viewport.
    assert int(case['work']['viewportId']) != 0
    assert all(math.isfinite(v) for v in bounds.values())
    assert bounds['width'] > 0 and bounds['height'] > 0
    assert bounds['x'] >= x + 8 - .02 and bounds['y'] >= y + 8 - .02
    assert bounds['x'] + bounds['width'] <= x + width - 8 + .02
    assert bounds['y'] + bounds['height'] <= y + height - 8 + .02
    if constrained:
        assert case['inventory']['absolute']
        intersections = []
        for monitor in case['inventory']['monitors']:
            left, top = max(0, monitor['x']), max(0, monitor['y'])
            right = min(960, monitor['x'] + monitor['width'])
            bottom = min(720, monitor['y'] + monitor['height'])
            if right > left and bottom > top:
                intersections.append(dict(x=left, y=top, width=right-left, height=bottom-top))
        assert work in intersections
    elif case['name'] == 'selector-empty':
        assert case['inventory']['absolute'] and not case['inventory']['monitors']
    else:
        assert not case['inventory']['absolute']
print('Native synthetic popup monitor/client intersections, text ownership and Cancel/Escape receipts PASS')
