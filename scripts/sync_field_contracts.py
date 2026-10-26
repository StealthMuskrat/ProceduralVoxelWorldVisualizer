"""Reject drift between the canonical model-generated contracts and checked-in files."""
from pathlib import Path
import json,sys
source=Path(sys.argv[1]);root=Path(__file__).resolve().parent.parent
paths=['schema/project.schema.json','schema/app-config.schema.json','registries/noise-processing-registry.json','registries/editor-creation-controls.json','fixtures/app-config-defaults.json','registries/trace-navigation.json','registries/algorithm-registry.json']
for name in paths:
 generated=(source/name).read_bytes()
 assert json.loads((root/name).read_bytes())==json.loads(generated),f'Stale generated contract: {name}'
print('Canonical model-generated schemas and registries PASS')
