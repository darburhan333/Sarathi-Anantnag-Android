from pathlib import Path
import subprocess, tempfile, shutil, json
root=Path(__file__).resolve().parents[1]
ext=root/'app/src/main/assets/extension'
for f in ext.glob('*.js'):
 subprocess.run(['node','--check',str(f)],check=True)
ui=(ext/'ui.js').read_text()
content=(ext/'content.js').read_text()
background=(ext/'background.js').read_text()
profile=(ext/'profile-config.js').read_text()
profile_generator=(root/'scripts/prepare-profiles.py').read_text()
assert "$('autoBookingEnabled').addEventListener('change'" in ui and 'autoBookingEnabled:false' in ui
assert 'MANUAL_BOOKING_ONLY' not in ui+content+background+profile+profile_generator
assert "if(booking && (!d.calendarLock||!d.autoBookingEnabled))" in background
manifest=json.loads((ext/'manifest.json').read_text())
assert not any('127.0.0.1' in x for x in manifest['permissions'])
subprocess.run(['node',str(root/'scripts/cov-row-tests.js')],check=True)
subprocess.run(['node',str(root/'scripts/vault-tests.js')],check=True)
subprocess.run(['node',str(root/'scripts/telegram-delivery-tests.js')],check=True)
subprocess.run(['node',str(root/'scripts/adapter-tests.js')],check=True)
subprocess.run(['node',str(root/'scripts/ui-native-stop.js')],check=True)
subprocess.run(['node',str(root/'scripts/background-lifecycle.js')],check=True)
with tempfile.TemporaryDirectory() as tmp:
 work=Path(tmp)/'extension';work.mkdir()
 for name in ['content.js','booking-rules.js','ui.js']:
  shutil.copyfile(ext/name,work/name)
 shutil.copytree(root/'scripts/content-tests',work/'tests')
 for test in sorted((work/'tests').glob('*.js')):
  subprocess.run(['node',str(test)],check=True)
print('PASS: Ported-code suites, Android adapter checks, production host permissions and syntax')

