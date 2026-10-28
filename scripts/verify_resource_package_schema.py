"""Deferred local-only envelope/project schema linkage and portable-fixture checks."""
from copy import deepcopy
from pathlib import Path
import json
import jsonschema


def validator(root):
    package = json.loads((root / 'schema/resource-package.schema.json').read_text())
    project = json.loads((root / 'schema/project.schema.json').read_text())
    jsonschema.Draft202012Validator.check_schema(package)
    assert package['properties']['resources'] == {'$ref': project['$id']}
    # Resolve the project URN against the checked-in schema, without network retrieval.
    local = deepcopy(package)
    local['properties']['resources'] = {'$ref': '#/$defs/ProjectData'}
    local['$defs'] = project['$defs']
    return jsonschema.Draft202012Validator(local, format_checker=jsonschema.FormatChecker())


def verify(root):
    check = validator(root)
    fixture = json.loads((root / 'fixtures/resource-package-empty.json').read_text())
    check.validate(fixture)
    invalid = []
    for field in ('packageVersion', 'resources'):
        candidate = deepcopy(fixture)
        del candidate[field]
        invalid.append(candidate)
    for version in (None, 0, 2, '1', True):
        candidate = deepcopy(fixture)
        candidate['packageVersion'] = version
        invalid.append(candidate)
    candidate = deepcopy(fixture)
    candidate['unknown'] = True
    invalid.append(candidate)
    candidate = deepcopy(fixture)
    del candidate['resources']['project']
    invalid.append(candidate)
    for candidate in invalid:
        assert not check.is_valid(candidate), 'Malformed portable envelope was accepted'
    print('Portable resource envelope/project schema and fixtures PASS')


if __name__ == '__main__':
    verify(Path(__file__).resolve().parent.parent)
