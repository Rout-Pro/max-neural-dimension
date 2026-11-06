#!/usr/bin/env python3
"""Seeds data into the DELIVERED v2.0.1 build before an in-place upgrade.
Runs against the old APK: adds a task so the post-upgrade driver can prove
Room data survived migration (v1 -> current)."""
import subprocess, time, sys

def sh(*args):
    return subprocess.run(list(args), capture_output=True, text=True)

def dump():
    sh('adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    r = sh('adb', 'shell', 'cat', '/sdcard/ui.xml')
    return r.stdout

def tap_text(t, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        if t in xml:
            # crude bounds parse
            import re
            for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
                if t in m.group(1):
                    x = (int(m.group(2)) + int(m.group(4))) // 2
                    y = (int(m.group(3)) + int(m.group(5))) // 2
                    sh('adb', 'shell', 'input', 'tap', str(x), str(y))
                    print('seed tapped', t)
                    return True
        time.sleep(2)
    print('seed MISS', t)
    return False

sh('adb', 'shell', 'monkey', '-p', 'com.max.neuraldimension', '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(12)
tap_text('Tools')
time.sleep(3)
if tap_text('Add a task...'):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'seed-task-v1')
    time.sleep(2)
    tap_text('Add', timeout=8)
    time.sleep(2)
ok = 'seed-task-v1' in dump()
print('SEED-RESULT:', 'ok' if ok else 'FAILED')
sys.exit(0 if ok else 1)
