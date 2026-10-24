"""Independently recompute native palette contrast from exported raw RGBA values."""
from pathlib import Path
import argparse
import json
import math
import jsonschema

parser = argparse.ArgumentParser()
parser.add_argument('preferences', type=Path)
parser.add_argument('report', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
value = json.loads(args.preferences.read_text(encoding='utf-8'))
schema = json.loads((root / 'schema/user-config.schema.json').read_text())
jsonschema.Draft202012Validator(schema).validate(value)
registry = json.loads((root / 'registries/theme-palette.json').read_text())
keys = {c['name']: str(c['key']) for c in registry['colors']}
report = json.loads(args.report.read_text(encoding='utf-8'))
assert report['formatVersion'] == 1
assert [p['name'] for p in report['profiles']] == ['Dark', 'Light', 'Alpha composition']


def over(a, b):
    alpha = a[3] + b[3] * (1 - a[3])
    if alpha == 0:
        return [0, 0, 0, 0]
    return [(a[i] * a[3] + b[i] * b[3] * (1 - a[3])) / alpha for i in range(3)] + [alpha]


def luminance(c):
    linear = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in c[:3]]
    return sum(x * y for x, y in zip(linear, [.2126, .7152, .0722]))


def ratio(a, b):
    x, y = luminance(a), luminance(b)
    return (max(x, y) + .05) / (min(x, y) + .05)


total = 0
for profile in report['profiles']:
    colors = profile['colors']
    assert set(colors) == set(keys.values())
    assert set(profile['statuses']) == set(registry['statusRoles'])
    for c in list(colors.values()) + list(profile['statuses'].values()):
        assert len(c) == 4 and all(type(v) in (int, float) and math.isfinite(v) and 0 <= v <= 1 for v in c)
    color = lambda name: colors[keys[name]]
    canvas = [0, 0, 0, 1] if profile['base'] == 'Dark' else [1, 1, 1, 1]
    window = over(color('WindowBg'), canvas)
    expected = {}
    for name in ['WindowBg', 'ChildBg', 'PopupBg', 'MenuBarBg', 'FrameBg', 'FrameBgHovered', 'FrameBgActive', 'Button', 'ButtonHovered', 'ButtonActive', 'Header', 'HeaderHovered', 'HeaderActive', 'Tab', 'TabActive', 'TabUnfocused', 'TabUnfocusedActive', 'TextSelectedBg']:
        bg = window if name == 'WindowBg' else over(color(name), window)
        expected['Text / ' + name] = (ratio(over(color('Text'), bg), bg), 4.5)
    expected['Secondary text / WindowBg'] = (ratio(over(color('TextDisabled'), window), window), 4.5)
    for role, c in profile['statuses'].items():
        expected[role + ' text / WindowBg'] = (ratio(over(c, window), window), 4.5)
    frame = over(color('FrameBg'), window)
    expected['Check mark / FrameBg'] = (ratio(over(color('CheckMark'), frame), frame), 3)
    expected['Slider grab / FrameBg'] = (ratio(over(color('SliderGrab'), frame), frame), 3)
    expected['Border / WindowBg'] = (ratio(over(color('Border'), window), window), 3)
    assert len(profile['checks']) == len(expected) == 26
    assert len({c['label'] for c in profile['checks']}) == 26
    for check in profile['checks']:
        measured, target = expected[check['label']]
        assert check['target'] == target
        assert math.isclose(check['ratio'], measured, rel_tol=2e-6, abs_tol=2e-6), (profile['name'], check, measured)
        total += 1
assert math.isclose(ratio([0, 0, 0, 1], [1, 1, 1, 1]), 21)
assert math.isclose(ratio(over([0, 0, 0, .5], [1, 1, 1, 1]), [1, 1, 1, 1]), 3.976653024912438)
print(f'Theme schema, raw native palettes, alpha composition and independent contrast PASS: {total} checks / 3 profiles')
