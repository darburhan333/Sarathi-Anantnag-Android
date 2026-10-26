"""Exercise the actual minified release UI, with no debug extension or fixture hooks."""
import subprocess,time,xml.etree.ElementTree as ET,re
PACKAGE='com.sarathi.anantnag.gecko'
def adb(*args):return subprocess.check_output(['adb',*args],text=True)
def snapshot():
 adb('shell','uiautomator','dump','/sdcard/release-ui.xml')
 return ET.fromstring(adb('shell','cat','/sdcard/release-ui.xml'))
def wait_ready(number):
 end=time.monotonic()+50
 while time.monotonic()<end:
  root=snapshot();texts=[n.get('text','') for n in root.iter('node')]
  if any(t.startswith('Applicant '+str(number)+' · STOPPED') for t in texts):return root
  time.sleep(1)
 raise AssertionError('Minified release bridge did not initialize for applicant '+str(number)+': '+str(texts))
adb('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
adb('shell','am','force-stop',PACKAGE)
adb('shell','am','start','-W','-n',PACKAGE+'/com.sarathi.geckotest.MainActivity')
root=wait_ready(1)
for number in [2,3,4,5,6,7,8,9,1]:
 node=next(n for n in root.iter('node') if n.get('text')=='Applicant '+str(number))
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));root=wait_ready(number)
adb('shell','screencap','-p','/data/local/tmp/release-preview.png')
adb('pull','/data/local/tmp/release-preview.png','release-preview.png')
adb('pull','/sdcard/release-ui.xml','release-ui.xml')
print('PASS: minified release starts and all nine applicant bridges connect')



