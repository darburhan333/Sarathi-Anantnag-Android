"""Exercise the actual minified release UI, with no debug extension or fixture hooks."""
import subprocess,time,xml.etree.ElementTree as ET,re
PACKAGE='com.sarathi.anantnag.gecko.v33'
def adb(*args):
 try:return subprocess.check_output(['adb',*args],text=True,timeout=35)
 except subprocess.TimeoutExpired as exc:
  print('ADB timeout: '+repr(args),flush=True)
  try:
   diagnostic=subprocess.check_output(['adb','logcat','-d','-t','300'],text=True,timeout=10)
   open('release-smoke-failure.txt','w').write('ADB timeout: '+repr(args)+'\n'+diagnostic)
   print(diagnostic,flush=True)
  except Exception as capture:print('Timeout evidence capture failed: '+repr(capture),flush=True)
  raise AssertionError('ADB command timed out: '+repr(args)) from exc
snapshot_count=0
def snapshot():
 global snapshot_count
 for attempt in range(3):
  adb('shell','uiautomator','dump','/sdcard/release-ui.xml')
  xml=adb('shell','cat','/sdcard/release-ui.xml')
  open('release-ui.xml','w').write(xml)
  root=ET.fromstring(xml)
  if snapshot_count==0:print('Initial release UI: '+repr([n.get('text','') for n in root.iter('node') if n.get('text')])[:1800],flush=True)
  snapshot_count+=1
  if not recover_system_anr(root):return root
 raise AssertionError('Emulator launcher/System UI remains unresponsive after bounded Wait recovery')
def recover_system_anr(root):
 # On a slow CI emulator, recover from either launcher or System UI ANR without force-closing apps.
 anr_titles={"Quickstep isn't responding","System UI isn't responding"}
 nodes=[n for n in root.iter('node') if n.get('text') in anr_titles]
 if not nodes:return False
 wait=next((n for n in root.iter('node') if n.get('text')=='Wait'),None)
 if wait is None:return False
 bounds=list(map(int,re.findall(r'\d+',wait.get('bounds',''))))
 if len(bounds)==4:
  adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
  time.sleep(4)
  return True
 return False
def wait_ready(number):
 end=time.monotonic()+120
 texts=[]
 while time.monotonic()<end:
  root=snapshot();texts=[n.get('text','') for n in root.iter('node')]
  if any(t.startswith('Applicant '+str(number)+' · STOPPED') for t in texts):return root
  if recover_system_anr(root):continue
  time.sleep(3)
 try:
  open('release-smoke-failure.txt','w').write(
   'Applicant: '+str(number)+'\nUI texts: '+repr(texts)+'\n\n'+
   adb('shell','dumpsys','activity','activities')+'\n\n'+
   adb('logcat','-d','-t','2500'))
 except Exception as exc:
  open('release-smoke-failure.txt','w').write('Diagnostic capture failed: '+repr(exc)+'\nUI texts: '+repr(texts))
 raise AssertionError('Minified release bridge did not initialize for applicant '+str(number)+': '+str(texts))
adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
adb('shell','am','force-stop',PACKAGE)
adb('shell','am','start','-W','-n',PACKAGE+'/com.sarathi.geckotest.MainActivity')
root=wait_ready(1)
def tap_node(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(0.5)
def tap_text(text):
 end=time.monotonic()+15
 while time.monotonic()<end:
  current=snapshot()
  node=next((n for n in current.iter('node') if n.get('text','').casefold()==text.casefold()),None)
  if node is not None:tap_node(node);return
  time.sleep(0.5)
 raise AssertionError('Required release UI control missing: '+text)
def settings_menu():
 current=snapshot()
 tap_node(next(n for n in current.iter('node') if n.get('content-desc')=='Open all settings'))
def wait_text(label):
 end=time.monotonic()+15
 while time.monotonic()<end:
  current=snapshot()
  if any(n.get('text')==label for n in current.iter('node')):return current
  time.sleep(.5)
 raise AssertionError('Required page content missing: '+label)
def choose_applicant(number):
 current=snapshot();tap_node(next(n for n in current.iter('node') if n.get('content-desc')=='Choose applicant'))
 tap_text('Applicant '+str(number));return wait_ready(number)
assert any(n.get('text')=='Home' for n in root.iter('node')),'Home must be directly available'
logo=next(n for n in root.iter('node') if n.get('content-desc')=='MPARIVAHAN app logo')
assert logo.get('clickable')=='false' and logo.get('long-clickable')=='false','Only hamburger may open Settings'
settings_menu();menu=snapshot()
for label in ['Phone numbers','Alerts','Logs','Background setup']:
 assert any(n.get('text')==label for n in menu.iter('node')),'Hamburger settings missing: '+label
adb('shell','screencap','-p','/data/local/tmp/release-settings.png');adb('pull','/data/local/tmp/release-settings.png','release-settings.png')
tap_text('Close')
for number in [2,3,4,5,6,7,8,9,1]:
 print('Release UI: checking Applicant '+str(number),flush=True)
 root=choose_applicant(number)
 assert not any(n.get('text','').startswith('Alerts · Applicant ') for n in root.iter('node')),'Selecting an applicant must not open Settings'
 settings_menu();tap_text('Alerts');wait_text('Alerts · Applicant '+str(number))
 alerts=wait_text('Chat ID 2 · optional')
 for label in ['Bot token','Chat ID 1','Chat ID 2 · optional','Save settings','Test Telegram']:
  assert any(n.get('text')==label for n in alerts.iter('node')),'Alerts must expose '+label
 assert not any(n.get('text') in ['LIVE MONITOR','Start this applicant','Ready to monitor'] for n in alerts.iter('node')),'Alerts must not contain the Monitor'
 if number==1:
  adb('shell','screencap','-p','/data/local/tmp/release-alerts.png');adb('pull','/data/local/tmp/release-alerts.png','release-alerts.png')
 print('Release UI: verified Alerts for Applicant '+str(number),flush=True)
 tap_text('Back');root=wait_ready(number);wait_text('LIVE MONITOR')
root=snapshot()
assert not any(n.get('content-desc')=='Applicant 1' for n in root.iter('node')),'Compact selector must keep the legacy applicant grid hidden'
print('Release UI: checking Logs isolation',flush=True)
settings_menu();tap_text('Logs');logs=wait_text('Activity & diagnostics')
assert not any(n.get('class','').endswith('GeckoView') for n in logs.iter('node')),'Logs must not contain a browser'
assert not any(n.get('text') in ['Start this applicant','LIVE MONITOR','Bot token'] for n in logs.iter('node')),'Logs must only contain activity and diagnostics'
filter_node=next(n for n in logs.iter('node') if n.get('content-desc')=='Filter logs by applicant');tap_node(filter_node);tap_text('Applicant 9')
adb('shell','screencap','-p','/data/local/tmp/release-logs.png');adb('pull','/data/local/tmp/release-logs.png','release-logs.png')
tap_text('Close');root=wait_ready(1)
assert any(n.get('text')=='Call A1' for n in root.iter('node')),'Log filtering must not switch the active applicant'
root=snapshot()
labels=['Website','Start All','Silence All','Stop All','Home','Call A1']
adb('shell','screencap','-p','/data/local/tmp/release-preview.png')
adb('pull','/data/local/tmp/release-preview.png','release-preview.png')
def native_control(root,label):
 matches=[n for n in root.iter('node') if n.get('text')==label and n.get('class')=='android.widget.Button' and n.get('package')==PACKAGE]
 assert len(matches)==1,'Expected one native Signature control '+label+': '+str([n.attrib for n in matches])
 return matches[0]
for label in labels:
 assert any(n.get('text')==label for n in root.iter('node')),'Signature control missing: '+label
control_nodes=[native_control(root,label) for label in labels]
rects=[list(map(int,re.findall(r'\d+',n.get('bounds')))) for n in control_nodes]
print('Native Signature control bounds: '+repr(dict(zip(labels,rects))),flush=True)
assert len({r[1] for r in rects[:3]})==1 and len({r[1] for r in rects[3:]})==1,'Controls must occupy exactly two aligned rows: '+repr(dict(zip(labels,rects)))
assert rects[0][1]<rects[3][1],'Control rows must keep the approved sequence'
monitor_heading=next(n for n in root.iter('node') if n.get('text')=='LIVE MONITOR')
heading_bounds=list(map(int,re.findall(r'\d+',monitor_heading.get('bounds'))))
assert max(r[3] for r in rects)<=heading_bounds[1],'Both control rows must be ABOVE the live monitor'
assert rects[0][0]<rects[1][0]<rects[2][0] and rects[3][0]<rects[4][0]<rects[5][0],'Control column order changed'
picker=next(n for n in root.iter('node') if n.get('content-desc')=='Choose applicant')
tap_node(picker);tap_text('Applicant 2');root=wait_ready(2)
assert any(n.get('text')=='Call A2' for n in root.iter('node')),'Quick call must follow selected applicant'
tap_text('Website');root=snapshot()
assert any(n.get('text')=='Monitor' for n in root.iter('node')),'Monitor button must toggle views'
tap_text('Monitor');root=snapshot()
assert any(n.get('text')=='Website' for n in root.iter('node')),'Website button must toggle back'
picker=next(n for n in root.iter('node') if n.get('content-desc')=='Choose applicant')
tap_node(picker);tap_text('Applicant 1');root=wait_ready(1)
adb('shell','screencap','-p','/data/local/tmp/release-preview.png')
adb('pull','/data/local/tmp/release-preview.png','release-preview.png')
adb('pull','/sdcard/release-ui.xml','release-ui.xml')
print('PASS: minified release starts and all nine applicant bridges connect')





# Rotation is handled without recreating the Activity; ensure the header yields space.
adb('shell','settings','put','system','accelerometer_rotation','0')
try:
 adb('shell','settings','put','system','user_rotation','1');time.sleep(2)
 landscape=snapshot()
 displays=[list(map(int,re.findall(r'\d+',n.get('bounds','')))) for n in landscape.iter('node') if n.get('class')=='android.widget.ScrollView']
 assert displays,'Scrollable native header missing after rotation'
 width=max(int(re.findall(r'\d+',n.get('bounds','[0,0][0,0]'))[2]) for n in landscape.iter('node'))
 height=max(int(re.findall(r'\d+',n.get('bounds','[0,0][0,0]'))[3]) for n in landscape.iter('node'))
 assert width>height,'Emulator failed to rotate to landscape'
 assert displays[0][3]-displays[0][1]<height*0.65,'Header consumes landscape page area'
 print('PASS: landscape header leaves website space')
finally:
 adb('shell','settings','put','system','user_rotation','0')
 adb('shell','settings','put','system','accelerometer_rotation','1')
