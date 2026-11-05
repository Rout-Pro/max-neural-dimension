# In-place upgrade persistence check: seed state on the delivered Phase 1 APK,
# then (after adb install -r of the hardened release) verify it survives.
# No provider calls: the seeded backend URL is https://localhost.invalid, which
# fails DNS resolution instantly on the emulator.
import re, subprocess, sys, time

PKG = 'com.max.neuraldimension'
MODE = sys.argv[1] if len(sys.argv) > 1 else 'seed'
SEED_MSG = 'Hardening seed message alpha'
SEED_URL = 'https://localhost.invalid'

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

def unesc(t):
    return (t.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
             .replace('&quot;', '"').replace('&apos;', "'"))

def tap(x, y):
    adb('shell', 'input', 'tap', str(x), str(y))

def find_node(xml, t, exact=False):
    for raw in xml.split('<node'):
        tm = re.search(r'text="([^"]*)"', raw)
        cm = re.search(r'content-desc="([^"]*)"', raw)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        if not bm:
            continue
        labels = []
        if tm and tm.group(1):
            labels.append(unesc(tm.group(1)))
        if cm and cm.group(1):
            labels.append(unesc(cm.group(1)))
        for txt in labels:
            if (exact and txt == t) or (not exact and t.lower() in txt.lower()):
                return tuple(int(bm.group(i)) for i in (1, 2, 3, 4)), labels
    return None, []

def settle(rounds=8):
    for _ in range(rounds):
        xml = dump()
        if 'isn\'t responding' in xml or 'isn\u2019t responding' in xml:
            b, _ = find_node(xml, 'Wait')
            if b:
                tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
                print('ANR dialog: tapped Wait'); sys.stdout.flush()
            time.sleep(4)
            continue
        return True
    return False

def launch():
    adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(6)
    settle()

def tap_text(t, timeout=15, exact=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        b, labels = find_node(xml, t, exact)
        if b:
            tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
            print('tapped', labels); sys.stdout.flush()
            return True
        time.sleep(1)
    print('MISS tap:', t); sys.stdout.flush()
    return False

def tap_below(label, dy=45, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        b, labels = find_node(xml, label)
        if b:
            tap((b[0] + b[2]) // 2, b[3] + dy)
            print('tapped below', labels); sys.stdout.flush()
            return True
        time.sleep(1)
    print('MISS tap_below:', label); sys.stdout.flush()
    return False

def type_text(s):
    for ch in s:
        if ch == ' ':
            adb('shell', 'input', 'text', '%s')
        else:
            adb('shell', 'input', 'text', "'" + ch + "'")
        time.sleep(0.12)

def dismiss_keyboard():
    r = adb('shell', 'dumpsys', 'input_method')
    if 'mInputShown=true' in (r.stdout or ''):
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1)
    else:
        print('dismiss_keyboard: no IME showing, skipping BACK')

def poll_text(t, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if t in dump():
            return True
        time.sleep(1)
    return False

def fail(msg):
    print('FAIL:', msg); sys.stdout.flush()
    shot('fail-%s' % MODE)
    sys.exit(1)

if MODE == 'seed':
    launch()
    tap_text('Settings') or fail('Settings tab')
    time.sleep(2)
    # Settings is a scrollable column; the SECURE BACKEND section is near the top.
    tap_below('Backend URL') or fail('Backend URL label')
    type_text(SEED_URL)
    dismiss_keyboard()
    time.sleep(1)
    if SEED_URL not in dump():
        # retry with a deeper offset in case the first tap missed the field
        tap_below('Backend URL', dy=70) or fail('Backend URL label retry')
        type_text(SEED_URL)
        dismiss_keyboard()
    poll_text(SEED_URL, 8) or fail('backend URL not entered')
    shot('seed-settings')
    tap_text('Chat') or fail('Chat tab')
    time.sleep(2)
    tap_text('Message MAX', timeout=20) or fail('chat input')
    type_text(SEED_MSG)
    tap_text('Send') or fail('Send button')
    poll_text(SEED_MSG, 30) or fail('seed message bubble not visible')
    time.sleep(8)  # let the instant DNS failure settle into an error bubble
    shot('seed-chat')
    print('SEED OK'); sys.stdout.flush()
elif MODE == 'verify':
    adb('shell', 'am', 'force-stop', PKG)
    launch()
    tap_text('Chat') or fail('Chat tab')
    poll_text(SEED_MSG, 40) or fail('seeded message missing after in-place upgrade')
    shot('upgraded-chat')
    tap_text('Settings') or fail('Settings tab')
    poll_text(SEED_URL, 25) or fail('seeded backend URL missing after in-place upgrade')
    shot('upgraded-settings')
    print('UPGRADE VERIFY PASS'); sys.stdout.flush()
else:
    print('usage: upgrade_check.py [seed|verify]')
    sys.exit(2)
