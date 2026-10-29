"""Deferred independent replay of workspace visibility, normal size and native docking report."""
import json
from pathlib import Path
import subprocess
import sys

out = Path(sys.argv[1])
root = Path(__file__).resolve().parent.parent
report = json.loads((out / 'workspace-native.json').read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
for field in ('resourcesUndockRedock', 'propertiesUndockRedock', 'resetSurvivedCapture',
              'freshContextDockRestore', 'toolVisibilityRestored', 'profilerStayedDisabled',
              'projectPreserved', 'normalSizeFixture', 'freshProjectEmpty'):
    assert report[field] is True, field
assert (report['width'], report['height']) == (1234, 789)
subprocess.run([sys.executable, str(root / 'scripts/verify_input_preferences.py'),
                str(out / 'user-config.json')], check=True)
user = json.loads((out / 'user-config.json').read_text(encoding='utf-8'))
workspace = user['workspace']
assert workspace['visibleWindows'] == ['Logs', 'Help', 'Profiler']
assert (workspace['width'], workspace['height']) == (1234, 789)
assert workspace['docking']
for name in ('Resources', 'Properties', 'Visualizers'):
    assert '[Window][' + name + ']' in workspace['docking']
assert '[Docking][Data]' in workspace['docking']
assert 'project' not in user and user['performance']['updateEndpoint'] == ''
assert not list(out.rglob('.user-config-*.tmp'))
print('Workspace saved visibility, native dock layout, normal-size fixture and empty restart replay PASS')
