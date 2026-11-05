import os, re, subprocess, sys, time

BACKEND_URL = os.environ.get('ND_TEST_BACKEND_URL', '').strip()
APP_TOKEN = os.environ.get('ND_TEST_APP_TOKEN', '').strip()
BACKEND_TEST = bool(BACKEND_URL and APP_TOKEN)

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
    # Always char-by-char: one adb call per character. A full-string
    # 'input text' drops characters on slow (SwiftShader) emulators, and the
    # per-char form also avoids %s-escape and shell-expansion of $ % + ^ etc.
    for ch in s:
        if ch == ' ':
            adb('shell', 'input', 'text', '%s')
        elif ch == "'":
            adb('shell', 'input', 'text', '"\'"'"'"'"'"''")
        else:
            adb('shell', 'input', 'text', "'" + ch + "'")
        time.sleep(0.12)

def parse_nodes(xml):
    nodes = []
    for raw in xml.split('<node'):
        tm = re.search(r'text="([^"]*)"', raw)
        cm = re.search(r'content-desc="([^"]*)"', raw)
        cl = re.search(r'class="([^"]*)"', raw)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        if not bm:
            continue
        nodes.append({'text': tm.group(1) if tm else '', 'desc': cm.group(1) if cm else '',
                      'cls': cl.group(1) if cl else '',
                      'b': tuple(int(bm.group(i)) for i in range(1, 5))})
    return nodes

def swipe_up():
    adb('shell', 'input', 'swipe', '540', '2000', '540', '900', '300')
    time.sleep(1)

def tap_field_below(label_prefix, timeout=12):
    # Tap the EditText directly below a label; scrolls down while searching.
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = parse_nodes(dump())
        labs = [n for n in nodes if n['text'].lower().startswith(label_prefix.lower())]
        edits = [n for n in nodes if 'EditText' in n['cls']]
        for l in labs:
            cands = [e for e in edits if l['b'][1] - 10 <= e['b'][1] <= l['b'][3] + 220]
            if cands:
                e = min(cands, key=lambda e: e['b'][1])
                tap((e['b'][0] + e['b'][2]) // 2, (e['b'][1] + e['b'][3]) // 2)
                print('tapped field below', label_prefix); sys.stdout.flush()
                return True
        dismiss_anr()
        swipe_up()
    print('MISS field below', label_prefix); sys.stdout.flush()
    return False

def dismiss_keyboard():
    # BACK collapses the IME without navigating when a keyboard is showing.
    # Needed before bottom-nav taps: covered Compose nodes are still in the
    # uiautomator tree, so a blind tap lands on a keyboard key instead.
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(1)

def dismiss_anr():
    # Emulator system ANR dialog ("<app> isn't responding") steals all input.
    # Always choose Wait - "Close app" would kill the foreground app.
    xml = dump()
    if 'responding' in xml and 'Wait' in xml:
        if tap_text('Wait', timeout=3, exact=True):
            print('dismissed ANR dialog via Wait'); sys.stdout.flush()
            time.sleep(2)
            return True
    return False

def tap_nav(label, timeout=12):
    # Bottom-nav tap that survives an open keyboard and ANR dialogs.
    if tap_text(label, timeout=3, exact=True):
        return True
    dismiss_keyboard()
    dismiss_anr()
    return tap_text(label, timeout=timeout, exact=True)

def assert_text(t, xml):
    ok = t.lower() in xml.lower()
    print(('PASS ' if ok else 'FAIL ') + 'expect text: ' + t); sys.stdout.flush()
    return ok

adb('shell', 'pm', 'grant', PKG, 'android.permission.RECORD_AUDIO')
adb('shell', 'pm', 'grant', PKG, 'android.permission.CAMERA')
adb('shell', 'am', 'force-stop', PKG)
adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(14)
for _ in range(8):
    if not dismiss_anr():
        break
    time.sleep(3)

if BACKEND_TEST:
    # Configure the secure backend via the real Settings UI (token arrives via
    # GitHub Actions secrets -> env; never in repo or APK).
    tap_nav('Settings')
    time.sleep(3)
    if tap_field_below('Backend URL'):
        time.sleep(1)
        type_text(BACKEND_URL)
        time.sleep(1)
    if tap_field_below('App token'):
        time.sleep(1)
        type_text(APP_TOKEN)
        time.sleep(1)
    dismiss_keyboard()
    tap_text('Test connection', timeout=15)
    time.sleep(6)
    shot('00-backend-test')
    xml = dump()
    assert_text('Backend reachable', xml)
    tap_nav('Home')
    time.sleep(4)

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
tap_text('Close', exact=True)
time.sleep(1)

# Chat: send a message; with no key configured the app must show the honest setup card
tap_nav('Chat')
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
if BACKEND_TEST:
    time.sleep(22)
    shot('04-chat-reply')
    xml = dump()
    print('chat screen after send:', 'BACKEND-REPLY-CHECK')
else:
    shot('04-chat-setup-honest')
    xml = dump()
    assert_text('Secure backend is not connected yet', xml)

# Voice tab
dismiss_keyboard()
tap_nav('Voice')
time.sleep(4)
shot('05-voice')
xml = dump()
assert_text('LIVE WITH', xml)

# Real TTS voice test -> speaking robot with mouth animation
# Evidence: screenrecord video of the speaking animation + logcat timeline of
# synthesis, envelope and playback-position/ampljectory pairs.
adb('logcat', '-c')
adb('shell', 'screenrecord', '--time-limit', '30', '/sdcard/max_voice.mp4', '&')
time.sleep(1)
if tap_text('Test MAX voice'):
    time.sleep(3)
    shot('06-voice-speaking')
    xml = dump()
    assert_text('SPEAKING', xml)
    time.sleep(4)
    shot('07-voice-speaking-2')
    time.sleep(5)
    shot('08-voice-after')
time.sleep(1)
adb('shell', 'pkill', '-f', 'screenrecord')
time.sleep(2)
adb('pull', '/sdcard/max_voice.mp4', 'shots/max_voice.mp4')
with open('shots/max_voice_logcat.txt', 'w') as f:
    r = adb('logcat', '-d', '-s', 'MAX_VOICE:I')
    f.write(r.stdout)

# Tools
tap_nav('Tools')
time.sleep(3)
shot('09-tools')
xml = dump()
assert_text('Flashlight', xml)
assert_text('Battery', xml)

# Settings
tap_nav('Settings')
time.sleep(3)
shot('10-settings')
xml = dump()
assert_text('SECURE BACKEND', xml)
assert_text('Test connection', xml)

print('DRIVE DONE')
