"""Read bounded diagnostic evidence only."""
from pathlib import Path
import re
for p in sorted(Path('previous-integration').rglob('*')):
 if not p.is_file():continue
 if p.name in ('cpuinfo','meminfo','test-results.log'):
  print('RAW_BEGIN',p,flush=True);print(p.read_text(errors='replace')[:220000],flush=True)
 elif any(x in p.name for x in ('normalMonitoringStartThenStop','realExtensionLoop','nineApplicantIsolationAndBackground')):
  print('LOG_BEGIN',p,flush=True)
  lines=p.read_text(errors='replace').splitlines()
  pattern=r'TestRunner|DIAG|Protection|Exception|ERROR|FATAL|ANR|[Ss]tack|Thread\[|"main"|GC freed|lowmemory|am_kill|am_anr|thread dump'
  kept=[line for line in lines if re.search(pattern,line)]
  print('\n'.join(kept)[-280000:],flush=True)
