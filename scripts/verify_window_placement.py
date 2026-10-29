"""Deferred independent replay of actual normal GLFW bounds and persisted placement."""
import json
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'window-placement-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for key in ('normalNativeCapture', 'centerControl', 'nativeWindowRecreation',
            'projectPreserved', 'dockingPreserved'):
    assert report[key] is True, key
for key in ('maximizedNativeVerified', 'minimizedNativeVerified', 'monitorRemovalNativeVerified'):
    assert report[key] is False, key  # Service definitions do not certify physical window management.
areas = report['workAreas']
assert areas and all(a['width'] > 0 and a['height'] > 0 for a in areas)
frame = report['frameInsets']
assert all(v >= 0 for v in frame.values())


def fits(bounds, area):
    left = bounds['x'] - frame['left']
    top = bounds['y'] - frame['top']
    available_width = area['width'] - frame['left'] - frame['right']
    available_height = area['height'] - frame['top'] - frame['bottom']
    assert bounds['width'] >= 400 and bounds['height'] >= 300
    return (left >= area['x'] and top >= area['y']
            and (available_width < 400 or bounds['x'] + bounds['width'] + frame['right'] <= area['x'] + area['width'])
            and (available_height < 300 or bounds['y'] + bounds['height'] + frame['bottom'] <= area['y'] + area['height']))


for name, field in (('recovered-user-config.json', 'recovered'),
                    ('captured-user-config.json', 'captured'),
                    ('user-config.json', 'centered'),
                    ('restarted-user-config.json', 'centered')):
    subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                    str(out / name)], check=True)
    user = json.loads((out / name).read_text(encoding='utf-8'))
    workspace = user['workspace']
    bounds = report[field]
    assert all(type(workspace[k]) is int and workspace[k] == bounds[k] for k in ('x', 'y', 'width', 'height'))
    assert workspace['maximized'] is False
    assert workspace['visibleWindows'] == ['Help']
    assert workspace['docking'] == 'placement fixture layout'
    assert any(fits(bounds, area) for area in areas)
    assert 'files' not in user
primary = areas[0]
centered = report['centered']
left = primary['x'] + frame['left']
top = primary['y'] + frame['top']
room_x = max(0, primary['width'] - frame['left'] - frame['right'] - centered['width'])
room_y = max(0, primary['height'] - frame['top'] - frame['bottom'] - centered['height'])
assert centered['x'] == left + room_x // 2
assert centered['y'] == top + room_y // 2
assert not list(out.rglob('.user-config-*.tmp'))
print('Window placement normal bounds, frame/work-area containment, primary centering and fresh native-window persistence PASS')
