"""Validate actual persisted preferences against schema and independent per-context key uniqueness."""
from pathlib import Path
import argparse
import json
import jsonschema

parser = argparse.ArgumentParser()
parser.add_argument('preferences', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
value = json.loads(args.preferences.read_text(encoding='utf-8'))
schema = json.loads((root / 'schema/user-config.schema.json').read_text())
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(value)
registry = json.loads((root / 'registries/keyboard-shortcuts.json').read_text())
actions = {a['id']: a for a in registry['actions']}
bindings = value['input']['shortcuts']
assert set(bindings) == set(actions)
contexts = {'GLOBAL': {'general', 'resources', 'curve'}, 'DOCUMENT': {'general', 'resources'},
            'RESOURCES': {'resources'}, 'CURVE': {'curve'}}
seen = {context: {} for context in ('general', 'resources', 'curve')}
for action, chord in bindings.items():
    assert len(chord.encode('utf-16-le')) // 2 <= registry['maximumBindingUtf16Units']
    if not chord:
        continue
    parts = chord.split('+')
    assert parts[-1] in registry['keys']
    assert parts[:-1] == [modifier for modifier in registry['modifiers'] if modifier in parts[:-1]]
    for context in contexts[actions[action]['scope']]:
        assert chord not in seen[context], (context, action, seen[context].get(chord))
        seen[context][chord] = action
print(f"Input preferences schema, canonical chords and context uniqueness PASS: {len(bindings)} commands")
