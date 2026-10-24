"""Write portable artifact hashes and validate saved exact-voxel regression vectors."""
from pathlib import Path
import csv, hashlib, json, argparse
p=argparse.ArgumentParser();p.add_argument('--benchmark',type=Path);p.add_argument('--hashes',type=Path);a=p.parse_args()
root=Path(__file__).resolve().parent.parent
if a.hashes:
 lines=[]
 for f in sorted((root/'build/release/jars').glob('*.jar'),key=lambda f:f.name):lines.append(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name)
 if len(lines)!=2:raise SystemExit('Expected desktop and engine JARs')
 a.hashes.parent.mkdir(parents=True,exist_ok=True)
 with a.hashes.open('w',newline='\n') as out:out.write('\n'.join(lines)+'\n')
if a.benchmark:
 expected=json.loads((root/'fixtures/world-hashes.json').read_text())['sceneHashes']
 rows=list(csv.DictReader(line for line in a.benchmark.open(encoding='utf-8-sig') if not line.startswith('#')))
 if set(r['scene'] for r in rows)!=set(expected):raise SystemExit('Benchmark scenes mismatch')
 for row in rows:
  if row['voxel_hash']!=expected[row['scene']]:raise SystemExit(f"Exact voxel hash mismatch: {row}")
 print('Exact voxel regression hashes PASS')
