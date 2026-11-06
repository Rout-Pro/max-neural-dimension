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
task_ok = 'seed-task-v1' in dump()

# --- seed a conversation + user message (Room conversations/messages) ---
sh('adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK')
time.sleep(1)
tap_text('Chat')
time.sleep(3)
msg_ok = False
if tap_text('Message MAX', timeout=10):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'seed-message-v1')
    time.sleep(2)
    tap_text('Send', timeout=8)
    time.sleep(4)
    msg_ok = 'seed-message-v1' in dump()

# --- seed settings (DataStore): profile name + backend URL ---
sh('adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK')
time.sleep(1)
tap_text('Settings')
time.sleep(3)

import re as _re
def tap_below(label, dy=60, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        m = _re.search(r'text="([^"]*' + _re.escape(label) + r'[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = int(m.group(5)) + dy
            sh('adb', 'shell', 'input', 'tap', str(x), str(y))
            print('seed tapped below', label)
            return True
        time.sleep(2)
    print('seed MISS below', label)
    return False

name_ok = False
if tap_below('Your name'):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'SeedUserV1')
    time.sleep(2)
    sh('adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1)
    name_ok = 'SeedUserV1' in dump()

url_ok = False
if tap_below('Backend URL'):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'https://seed-settings-v1.invalid')
    time.sleep(2)
    sh('adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1)
    url_ok = 'seed-settings-v1' in dump()

ok = task_ok and msg_ok and name_ok and url_ok
print('SEED-RESULT: task=%s msg=%s name=%s url=%s' % (task_ok, msg_ok, name_ok, url_ok))
sys.exit(0 if ok else 1)
