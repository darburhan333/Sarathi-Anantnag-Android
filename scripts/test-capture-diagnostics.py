import importlib.util
from pathlib import Path
import unittest
import tempfile
import os
import io
from contextlib import redirect_stdout
from unittest.mock import patch
from subprocess import CompletedProcess
spec=importlib.util.spec_from_file_location('capture',Path(__file__).with_name('capture-diagnostics.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
GOOD='DIAG-START\n=== CRITICAL EVENTS (never trimmed) ===\ncritical\n=== CURRENT DIAGNOSTICS ===\nReady\nDIAG-END\n'
class CaptureTest(unittest.TestCase):
 def test_command_error_is_not_a_report(self):
  self.assertEqual('run-as: unknown package: example',m.first_error('run-as: unknown package: example\n'))
 def test_each_marker_must_be_a_standalone_line(self):
  for marker in m.MARKERS:
   self.assertIsNotNone(m.first_error(GOOD.replace(marker,'prefix'+marker)))
 def test_last_complete_report_survives_partial_tail(self):
  self.assertEqual(GOOD,m.last_complete_report(GOOD+'DIAG-START\npartial\n'))
 def test_concatenated_end_is_rejected(self):
  self.assertEqual('',m.last_complete_report(GOOD.replace('Ready\nDIAG-END','ReadyDIAG-END')))
 def test_marker_order_is_required(self):
  self.assertIsNotNone(m.first_error('\n'.join(reversed(GOOD.splitlines()))))
 def test_failed_private_route_falls_back_to_valid_handoff(self):
  previous=os.getcwd()
  try:
   with tempfile.TemporaryDirectory() as folder:
    os.chdir(folder)
    def run(command,**kwargs):
     if command[:3]==['adb','exec-out','run-as']:return CompletedProcess(command,0,'run-as: unknown package: example\n','')
     if command[-1]=='/data/local/tmp/monitor-copy-log.txt':return CompletedProcess(command,0,GOOD,'')
     return CompletedProcess(command,1,'','unavailable')
    with patch.object(m.subprocess,'run',side_effect=run):self.assertEqual(0,m.capture())
    self.assertEqual(GOOD,Path('monitor-copy-log.txt').read_text())
    status=Path('emulator-diagnostic-capture-status.txt').read_text()
    self.assertIn('app-private: run-as:',status)
    self.assertIn('instrumentation handoff',status)
    self.assertIn('pm list packages',status)
    self.assertIn('pm list users',status)
  finally:os.chdir(previous)
 def test_all_failed_routes_fail_closed_with_each_reason(self):
  previous=os.getcwd()
  try:
   with tempfile.TemporaryDirectory() as folder:
    os.chdir(folder)
    with patch.object(m.subprocess,'run',side_effect=lambda cmd,**kw:CompletedProcess(cmd,0,'partial\n','')), redirect_stdout(io.StringIO()):
     self.assertEqual(1,m.capture())
    self.assertFalse(Path('monitor-copy-log.txt').exists())
    status=Path('emulator-diagnostic-capture-status.txt').read_text()
    for route in ['app-private:','external-files:','instrumentation handoff:','DIAG logcat:']:
     self.assertIn(route,status)
  finally:os.chdir(previous)
if __name__=='__main__':unittest.main()
