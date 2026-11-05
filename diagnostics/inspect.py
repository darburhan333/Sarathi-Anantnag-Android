"""Read a previous run's evidence without changing or rerunning app tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
import re
root=Path('previous-integration')
for p in sorted(root.rglob('*')):
 if p.is_file():print('FILE',p,p.stat().st_size,flush=True)
for p in sorted(root.rglob('*.xml')):
 try:tree=ET.parse(p).getroot()
 except (ET.ParseError,OSError):continue
 cases=list(tree.iter('testcase'))
 if not cases:continue
 print('SUITE',p,tree.attrib,flush=True)
 for case in cases:
  failures=list(case.findall('failure'))+list(case.findall('error'))
  print('CASE',case.attrib,'FAILED' if failures else 'PASS',flush=True)
  for failure in failures:
   if 'normalMonitoringStartThenStop' in case.get('name',''):
    print('FIRST_STOP_FAILURE',failure.attrib,(failure.text or '')[:45000],flush=True)
for p in sorted(root.rglob('*')):
 if not p.is_file() or p.suffix.lower() not in ('.txt','.log'):continue
 data=p.read_text(errors='replace')
 matches=list(re.finditer(r'Thread\[main|Name: main|"main"|THREAD_DUMP|main thread|ANR in|FATAL EXCEPTION|WAITING|BLOCKED',data))
 print('TEXT_EVIDENCE',p,'matches',len(matches),flush=True)
 if matches:
  printed=[]
  for match in matches[:5]+matches[-15:]:
   start=max(0,match.start()-400);end=min(len(data),match.start()+7000)
   if any(start>=a and end<=b for a,b in printed):continue
   printed.append((start,end));print(data[start:end],flush=True)
 else:print(data[-15000:],flush=True)
