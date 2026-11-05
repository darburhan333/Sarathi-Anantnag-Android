"""Inspect exact Activity-launch failure and preceding case."""
from pathlib import Path
import re
for p in sorted(Path('previous-integration').rglob('*')):
 if not p.is_file():continue
 print('FILE',p,p.stat().st_size,flush=True)
 if 'stopDuringCalendarReviewDisarmsAndKeepsProtectedPageActive' in p.name or 'simultaneousSessionExpiryEvidenceIsPerApplicantAndRedacted' in p.name or 'applicantSettingsLockAndVisibilityDoNotStopOtherApplicants' in p.name:
  print('FULL_LOG',p,flush=True);print(p.read_text(errors='replace')[:300000],flush=True)
 elif p.suffix in ('.txt','.log'):
  lines=p.read_text(errors='replace').splitlines()
  for i,line in enumerate(lines):
   if re.search(r'Thread\[main|"main"|THREAD:|THREAD DUMP|thread dump|Dumping thread|Name: main',line):
    print('THREAD_CONTEXT',p,flush=True);print('\n'.join(lines[max(0,i-2):i+65]),flush=True)
