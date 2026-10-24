"""Deterministic development distribution. Supply the tested Windows runtime explicitly."""
from pathlib import Path
import argparse, hashlib, shutil, subprocess, zipfile, os
parser=argparse.ArgumentParser()
parser.add_argument('--runtime',type=Path,required=True)
parser.add_argument('--mesa-notices',type=Path)
parser.add_argument('--output',type=Path,default=Path('build/packages'))
a=parser.parse_args(); root=Path(__file__).resolve().parent.parent
out=(root/a.output).resolve(); out.mkdir(parents=True,exist_ok=True)
stage=out/'ProceduralVoxelCreator-Development'
if stage.exists():shutil.rmtree(stage)
stage.mkdir()
for name in ['ProceduralVoxelCreator.jar','noise-generator-engine.jar']:
 source=root/'build/release/jars'/name
 if not source.is_file():raise SystemExit(f'Build releaseJars first: {source}')
 (stage/'jars').mkdir(exist_ok=True);shutil.copy2(source,stage/'jars'/name)
if not (a.runtime/'bin/java.exe').exists():raise SystemExit('Expected Windows x64 runtime bin/java.exe')
shutil.copytree(a.runtime,stage/'windows/runtime');shutil.copytree(root/'desktop-package/windows/launcher',stage/'windows/launcher')
for name in ['docs','fixtures']:
 shutil.copytree(root/name,stage/name)
if a.mesa_notices:shutil.copytree(a.mesa_notices,stage/'docs/mesa-notices')
# Package only tracked source, once; never include caches, nested build outputs or credentials.
files=subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
for relative in filter(None,files):
 source=root/relative
 if not source.is_file():continue
 target=stage/'source'/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
example=stage/'examples/independent-engine-client';example.mkdir(parents=True)
for source in (root/'example-client/src/main/java/io/voxelcreator/example').glob('*.java'):shutil.copy2(source,example/source.name)
(example/'run.cmd').write_text('@echo off\r\nmkdir classes 2>nul\r\njavac -cp "..\\..\\jars\\noise-generator-engine.jar" -d classes *.java\r\nif errorlevel 1 exit /b 1\r\njava -cp "classes;..\\..\\jars\\noise-generator-engine.jar" io.voxelcreator.example.Example output\r\n')
for module in ['engine-core','engine-api','voxel-storage','editor-render','editor-ui']:
 reports=root/module/'build/test-results/test'
 if reports.exists():shutil.copytree(reports,stage/'test-reports'/module)
for source in (root/'build/smoke').glob('*'):
 if source.is_file():
  target=stage/'test-reports/native-smoke'/source.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
if (root/'build/windows-smoke.log').exists():shutil.copy2(root/'build/windows-smoke.log',stage/'test-reports/windows-smoke.log')
manifest=[]
for f in sorted(stage.rglob('*')):
 if f.is_file():manifest.append(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.relative_to(stage).as_posix())
(stage/'SHA256SUMS.txt').write_text('\n'.join(manifest)+'\n')
archive=out/'ProceduralVoxelCreator-Development-Windows-x64.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for f in sorted(stage.rglob('*')):
  if f.is_file():
   info=zipfile.ZipInfo(stage.name+'/'+f.relative_to(stage).as_posix(),(2020,1,1,0,0,0));info.external_attr=(0o100755 if f.name in ('gradlew',) else 0o100644)<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,f.read_bytes())
(archive.with_suffix('.zip.sha256')).write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
print(archive)
