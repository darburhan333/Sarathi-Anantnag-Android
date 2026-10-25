from pathlib import Path
import subprocess, json, hashlib, tempfile, shutil
root=Path(__file__).resolve().parents[1]
base=root/'baseline-v11.47'
ext=root/'app/src/main/assets/extension'
info=json.loads((base/'BUILD_INFO.json').read_text())
for n,h in info['sha256'].items():
 assert hashlib.sha256((base/n).read_bytes()).hexdigest()==h,n
for f in sorted((base/'tests').glob('*.js')):
 subprocess.run(['node',str(f)],check=True)
for f in ext.glob('*.js'):
 subprocess.run(['node','--check',str(f)],check=True)
subprocess.run(['node',str(root/'scripts/adapter-tests.js')],check=True)
# Re-run production DOM/loop suites with the actual ported content and rules.
with tempfile.TemporaryDirectory() as tmp:
 work=Path(tmp)/'extension';shutil.copytree(base,work)
 for name in ['content.js','booking-rules.js','ui.js']:
  shutil.copyfile(ext/name,work/name)
 for name in ['calendar_rules','quota_dom_140','proceed_140','restart_139','status_139','synthetic_301','hidden_144','ui_140']:
  subprocess.run(['node',str(work/'tests'/f'{name}.js')],check=True)
print('PASS: source hashes, 15 baseline suites, 8 ported suites, Android adapter checks and syntax')
