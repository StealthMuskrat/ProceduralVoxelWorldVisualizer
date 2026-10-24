"""Validate a persisted app profile and independent typed/default/choice ownership constraints."""
from pathlib import Path
import argparse
import json
import math
import jsonschema

parser = argparse.ArgumentParser()
parser.add_argument('profile', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
profile = json.loads(args.profile.read_text(encoding='utf-8'))
schema = json.loads((root / 'schema/app-config.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(profile)
registry = json.loads((root / 'registries/editor-creation-controls.json').read_text())['controls']
for key, value in profile.get('creationDefaults', {}).items():
    spec = registry[key]
    kind = spec['kind']
    if kind == 'BOOLEAN':
        assert type(value) is bool
    elif kind == 'LONG':
        assert type(value) is int and -(2 ** 63) <= value < 2 ** 63
    elif kind == 'CHOICE':
        assert value in spec['options']
    elif kind == 'TEXT':
        assert isinstance(value, str) and len(value.encode('utf-16-le')) // 2 <= 8192
    else:
        values = value if kind.startswith('VECTOR') else [value]
        if kind.startswith('VECTOR'):
            assert len(values) == int(kind[-1])
        for number in values:
            assert type(number) in (int, float) and math.isfinite(number)
            assert spec['minimum'] <= number <= spec['maximum']
            if kind == 'INTEGER':
                assert number == math.trunc(number)
for key, control in profile.get('controls', {}).items():
    allowed = registry.get(key, {}).get('options', [])
    assert set(control.get('options', {})) <= set(allowed)
    for field in ('label', 'group'):
        assert not any(ord(c) < 32 for c in control.get(field, ''))
print(f"Application profile schema, typed defaults and immutable choices PASS: {len(profile.get('creationDefaults', {}))} overrides")
