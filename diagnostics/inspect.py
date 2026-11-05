"""Print selected raw evidence; no test execution or app changes."""
from pathlib import Path
root=Path('previous-integration')
for p in sorted(root.rglob('*')):
 if not p.is_file():continue
 if p.name in ('cpuinfo','meminfo','test-results.log') or any(x in p.name for x in ('normalMonitoringStartThenStop','realExtensionLoop','nineApplicantIsolationAndBackground')):
  print('RAW_BEGIN',p,flush=True)
  print(p.read_text(errors='replace'),flush=True)
  print('RAW_END',p,flush=True)
