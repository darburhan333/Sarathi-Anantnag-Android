from pathlib import Path
import shutil,json
root=Path(__file__).resolve().parents[1]
for mode in ['main','debug']:
 assets=root/f'app/src/{mode}/assets'
 for number in [2,3]:
  target=assets/f'extension{number}'
  if target.exists():shutil.rmtree(target)
  shutil.copytree(assets/'extension',target)
  m=json.loads((target/'manifest.json').read_text());m['browser_specific_settings']['gecko']['id']=f'sarathi-applicant{number}@burhan.local'
  (target/'manifest.json').write_text(json.dumps(m,indent=2)+'\n')
  (target/'profile-config.js').write_text(f'globalThis.PROFILE_MODE=true;globalThis.APPLICANT={number};\n')
