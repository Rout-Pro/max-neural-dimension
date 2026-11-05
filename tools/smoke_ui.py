# Isolated environment + one-feature probe: bounded settle, hook-seeded mock
# backend, ONE message, expect a visible MOCK reply. Exits 1 fast on any
# environment storm or missing reply. This is the gate before any fuller suite.
import os, re, subprocess, sys, time

PKG = 'com.max.neuraldimension'
BACKEND_URL = os.environ.get('ND_TEST_BACKEND_URL', '').strip()
APP_TOKEN = os.environ.get('ND_TEST_APP_TOKEN', '').strip()

def adb(*args):
    return subprocess.run(['adb'] + list(args), capture_output=True, text=True)

def shot(name):
    with open('shots/%s.png' % name, 'wb') as f:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f)
    print('shot', name); sys.stdout.flush()

def dump(path='/tmp/ui.xml'):
    for _ in range(3):
        adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        subprocess.run(['adb', 'pull', '/sdcard/ui.xml', path], capture_output=True)
        try:
            return open(path, encoding='utf-8', errors='ignore').read()
        except FileNotFoundError:
            time.sleep(2)
    return ''

def find_node(xml, t, exact=False):
    for raw in xml.split('<node'):
        tm = re.search(r'text="([^"]*)"', raw)
        cm = re.search(r'content-desc="([^"]*)"', raw)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        if not bm:
            continue
        for g in (tm, cm):
            if g and g.group(1) and ((exact and g.group(1) == t) or (not exact and t.lower() in g.group(1).lower())):
                return tuple(int(bm.group(i)) for i in (1, 2, 3, 4))
    return None

def tap(x, y):
    adb('shell', 'input', 'tap', str(x), str(y))

def tap_text(t, timeout=10, exact=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        b = find_node(dump(), t, exact)
        if b:
            tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
            print('tapped', t); sys.stdout.flush()
            return True
        time.sleep(1)
    print('MISS tap:', t); sys.stdout.flush()
    return False

def type_text(s):
    for ch in s:
        adb('shell', 'input', 'text', '%s' if ch == ' ' else "'" + ch + "'")
        time.sleep(0.12)

def settle(timeout=90):
    deadline = time.time() + timeout
    quiet = 0
    while time.time() < deadline:
        xml = dump()
        if "isn't responding" in xml or 'is not responding' in xml:
            quiet = 0
            tap_text('Wait', timeout=2)
            time.sleep(4)
        else:
            quiet += 1
            if quiet >= 2:
                return True
            time.sleep(3)
    return False

def env_blocked(where):
    shot('env-blocked-' + where)
    print('ENV-BLOCKED at %s: emulator ANR storm outlasted settle budget' % where)
    sys.stdout.flush()
    sys.exit(1)

adb('shell', 'pm', 'grant', PKG, 'android.permission.CAMERA')
adb('shell', 'am', 'force-stop', PKG)
adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(12)
if not settle(90):
    env_blocked('start')

adb('shell', 'am', 'force-stop', PKG)
adb('shell', 'am', 'start', '-n', PKG + '/.MainActivity',
    '--es', 'nd_backend_url', "'" + BACKEND_URL + "'",
    '--es', 'nd_app_token', "'" + APP_TOKEN + "'")
time.sleep(8)
if not settle(60):
    env_blocked('hook-launch')

xml = dump()
if 'Quick Actions' in xml:
    tap_text('Close', timeout=3, exact=True)
    time.sleep(1)
if not tap_text('Chat', timeout=15, exact=True):
    shot('smoke-no-chat'); print('SMOKE-FAIL: Chat nav missing'); sys.exit(1)
time.sleep(2)
if not tap_text('Message MAX', timeout=20):
    shot('smoke-no-input'); print('SMOKE-FAIL: chat input missing'); sys.exit(1)
type_text('smoke ping')
if not tap_text('Send'):
    shot('smoke-no-send'); print('SMOKE-FAIL: Send missing'); sys.exit(1)

deadline = time.time() + 45
ok = False
while time.time() < deadline:
    if 'MOCK' in dump():
        ok = True
        break
    time.sleep(2)
shot('smoke-reply')
if not ok:
    print('SMOKE-FAIL: no visible MOCK reply within 45s')
    sys.exit(1)
print('SMOKE PASS: environment stable + one mock reply visible')
sys.stdout.flush()
