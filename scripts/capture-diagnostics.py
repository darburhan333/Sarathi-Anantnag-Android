"""Capture a complete test diagnostic report; reject command errors and partial reports."""
from pathlib import Path
import subprocess
import sys

PACKAGE = 'com.sarathi.anantnag.gecko.v33'
MARKERS = ('DIAG-START', 'DIAG-END', '=== CRITICAL EVENTS (never trimmed) ===',
           '=== CURRENT DIAGNOSTICS ===')

def first_error(data):
    lines = data.splitlines()
    if not lines:
        return 'empty capture'
    for line in lines:
        if line.startswith(('run-as:', 'adb: error:', 'cat:')):
            return line
    for marker in MARKERS:
        if marker not in lines:
            return 'missing standalone marker: ' + marker
    if not (lines.index(MARKERS[0]) < lines.index(MARKERS[2]) <
            lines.index(MARKERS[3]) < lines.index(MARKERS[1])):
        return 'diagnostic markers out of order'
    return None

def last_complete_report(raw):
    current = []
    last = ''
    for line in raw.splitlines():
        if line == 'DIAG-START':
            current = [line]
        elif current:
            current.append(line)
            if line == 'DIAG-END':
                candidate = '\n'.join(current) + '\n'
                if first_error(candidate) is None:
                    last = candidate
                current = []
    return last

def capture():
    output = Path('monitor-copy-log.txt')
    output.unlink(missing_ok=True)
    status = Path('emulator-diagnostic-capture-status.txt')
    rejected = []
    routes = [
        ('app-private', ['adb', 'exec-out', 'run-as', PACKAGE, 'cat', 'files/monitor-copy-log.txt']),
        ('external-files', ['adb', 'shell', 'cat', f'/sdcard/Android/data/{PACKAGE}/files/monitor-copy-log.txt']),
        ('instrumentation handoff', ['adb', 'shell', 'cat', '/data/local/tmp/monitor-copy-log.txt']),
        ('DIAG logcat', ['adb', 'logcat', '-d', '-v', 'raw', '-s', 'DIAG:I', '*:S']),
    ]
    accepted = False
    with status.open('a') as log:
        for name, command in routes:
            result = subprocess.run(command, capture_output=True, text=True, errors='replace')
            data = last_complete_report(result.stdout) if name == 'DIAG logcat' else result.stdout.replace('\r\n', '\n')
            error = first_error(data)
            if result.returncode:
                error = next(iter((result.stderr or result.stdout).splitlines()), 'command failed')
            if error:
                rejected.append(name + ': ' + error)
                log.write(rejected[-1] + '\n')
                continue
            output.write_text(data)
            log.write('Validated complete diagnostic report from ' + name + '\n')
            accepted = True
            break
        if rejected:
            for command in (['adb', 'shell', 'pm', 'list', 'packages'], ['adb', 'shell', 'pm', 'list', 'users']):
                result = subprocess.run(command, capture_output=True, text=True, errors='replace')
                data = result.stdout
                if command[-1] == 'packages':
                    data = '\n'.join(line for line in data.splitlines() if 'gecko' in line)
                log.write(' '.join(command) + '\n' + data + '\n' + result.stderr)
        if not accepted:
            log.write('ALL ROUTES FAILED: ' + '; '.join(rejected) + '\n')
    if not accepted:
        print('::error::Diagnostic capture failed: ' + '; '.join(rejected))
        return 1
    print('PASS: complete monitor diagnostics captured with all four exact markers')
    return 0

if __name__ == '__main__':
    sys.exit(capture())
