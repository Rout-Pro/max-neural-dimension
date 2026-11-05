import re, subprocess, sys, time

PKG = 'com.max.neuraldimension'

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

def tap(x, y):
    adb('shell', 'input', 'tap', str(x), str(y))

def tap_text(t, timeout=12, exact=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        xml = dump()
        for node in xml.split('<node'):
            tm = re.search(r'text="([^"]*)"', node)
            cm = re.search(r'content-desc="([^"]*)"', node)
            bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if not bm:
                continue
            labels = []
            if tm and tm.group(1):
                labels.append(tm.group(1))
            if cm and cm.group(1):
                labels.append(cm.group(1))
            hit = False
            for txt in labels:
                if (exact and txt == t) or (not exact and t.lower() in txt.lower()):
                    hit = True
                    break
            if hit:
                x1, y1, x2, y2 = int(bm.group(1)), int(bm.group(2)), int(bm.group(3)), int(bm.group(4))
                tap((x1 + x2) // 2, (y1 + y2) // 2)
                print('tapped', labels); sys.stdout.flush()
                return True
        time.sleep(1)
    print('MISS tap:', t); sys.stdout.flush()
    return False

def type_text(s):
    adb('shell', 'input', 'text', s.replace(' ', '%s'))

def assert_text(t, xml):
    ok = t.lower() in xml.lower()
    print(('PASS ' if ok else 'FAIL ') + 'expect text: ' + t); sys.stdout.flush()
    return ok

adb('shell', 'pm', 'grant', PKG, 'android.permission.RECORD_AUDIO')
adb('shell', 'pm', 'grant', PKG, 'android.permission.CAMERA')
adb('shell', 'am', 'force-stop', PKG)
adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(14)

shot('01-home-robot')
xml = dump()
assert_text('NEURAL DIMENSION', xml)
assert_text('AI STATUS', xml)
assert_text('TASKS', xml)

time.sleep(6)
shot('02-home-idle-anim')

tap_text('AI STATUS')
time.sleep(2)
shot('03-panel-ai-status')
xml = dump()
assert_text('Provider', xml)
tap_text('Close')
time.sleep(1)

# Chat: send a message; with no key configured the app must show the honest setup card
tap_text('Chat', exact=True)
time.sleep(3)
xml = dump()
assert_text('New conversation', xml)
# focus input and type
if tap_text('Message', timeout=6):
    time.sleep(1)
    type_text('Hello MAX')
    time.sleep(1)
    adb('shell', 'input', 'keyevent', '66')
    time.sleep(1)
    tap_text('Send', timeout=3)
    time.sleep(4)
shot('04-chat-setup-honest')
xml = dump()
assert_text('Live AI is not configured yet', xml)

# Voice tab
tap_text('Voice', exact=True)
time.sleep(4)
shot('05-voice')
xml = dump()
assert_text('LIVE WITH', xml)

# Real TTS voice test -> speaking robot with mouth animation
if tap_text('Test MAX voice'):
    time.sleep(2)
    shot('06-voice-speaking')
    time.sleep(6)
    shot('07-voice-after')

# Tools
tap_text('Tools', exact=True)
time.sleep(3)
shot('08-tools')
xml = dump()
assert_text('Flashlight', xml)
assert_text('Battery', xml)

# Settings
tap_text('Settings', exact=True)
time.sleep(3)
shot('09-settings')
xml = dump()
assert_text('AI Provider', xml)
assert_text('Test connection', xml)

print('DRIVE DONE')
