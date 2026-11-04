"""Exercise the actual minified release UI, with no debug extension or fixture hooks."""
import subprocess,time,xml.etree.ElementTree as ET,re
PACKAGE='com.sarathi.anantnag.gecko.v33'
def adb(*args):return subprocess.check_output(['adb',*args],text=True)
def snapshot():
 adb('shell','uiautomator','dump','/sdcard/release-ui.xml')
 return ET.fromstring(adb('shell','cat','/sdcard/release-ui.xml'))
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
def logo_menu():
 current=snapshot()
 logo=next(n for n in current.iter('node') if n.get('content-desc')=='Tap for quick menu; hold for applicant settings')
 tap_node(logo)
assert any(n.get('text')=='Home' for n in root.iter('node')),'Home must be directly available'
def toggle_applicant_grid():
 current=snapshot()
 logo=next(n for n in current.iter('node') if n.get('content-desc')=='Tap for quick menu; hold for applicant settings')
 x1,y1,x2,y2=map(int,re.findall(r'\d+',logo.get('bounds')))
 x,y=str((x1+x2)//2),str((y1+y2)//2)
 adb('shell','input','swipe',x,y,x,y,'900')
 tap_text('Show / hide applicants');tap_text('Done')
logo_menu()
menu=snapshot()
for label in ['Phone numbers','Alerts','Logs','Background setup']:
 assert any(n.get('text')==label for n in menu.iter('node')),'Hamburger settings missing: '+label
adb('shell','input','keyevent','KEYCODE_BACK')
toggle_applicant_grid()
for number in [2,3,4,5,6,7,8,9,1]:
 root=snapshot()
 node=next(n for n in root.iter('node') if n.get('content-desc')=='Applicant '+str(number))
 tap_node(node)
 settings=snapshot()
 assert any(n.get('text','').startswith('LOCKED · Applicant '+str(number)+' ·') for n in settings.iter('node')),'Applicant settings must open full screen for selected applicant'
 tap_text('Done')
 root=wait_ready(number)
toggle_applicant_grid()
root=snapshot()
assert not any(n.get('content-desc')=='Applicant 1' for n in root.iter('node')),'Hide must restore clean main screen'
root=snapshot()
labels=['Website','Start All','Silence All','Stop All','Home','Call A1']
for label in labels:
 assert any(n.get('text')==label for n in root.iter('node')),'Signature control missing: '+label
control_nodes=[next(n for n in root.iter('node') if n.get('text')==label) for label in labels]
rects=[list(map(int,re.findall(r'\d+',n.get('bounds')))) for n in control_nodes]
assert len({r[1] for r in rects[:3]})==1 and len({r[1] for r in rects[3:]})==1,'Controls must occupy exactly two aligned rows'
assert rects[0][1]<rects[3][1],'Control rows must keep the approved sequence'
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
