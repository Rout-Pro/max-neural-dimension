import os, re, subprocess, sys, time

BACKEND_URL = os.environ.get('ND_TEST_BACKEND_URL', '').strip()
APP_TOKEN = os.environ.get('ND_TEST_APP_TOKEN', '').strip()
BACKEND_TEST = bool(BACKEND_URL and APP_TOKEN)
ONLY = set(x.strip() for x in os.environ.get('ND_SECTIONS', 'all').split(','))
def want(name): return 'all' in ONLY or name in ONLY


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

def unesc(t):
    return (t.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
             .replace('&quot;', '"').replace('&apos;', "'"))

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
                labels.append(unesc(tm.group(1)))
            if cm and cm.group(1):
                labels.append(unesc(cm.group(1)))
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
        nodes.append({'text': unesc(tm.group(1)) if tm else '', 'desc': unesc(cm.group(1)) if cm else '',
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

def field_text_below(label_prefix):
    nodes = parse_nodes(dump())
    labs = [n for n in nodes if n['text'].lower().startswith(label_prefix.lower())]
    edits = [n for n in nodes if 'EditText' in n['cls']]
    for l in labs:
        cands = [e for e in edits if l['b'][1] - 10 <= e['b'][1] <= l['b'][3] + 220]
        if cands:
            return min(cands, key=lambda e: e['b'][1])['text']
    return None

def clear_field_verified():
    for _ in range(3):
        clear_field()
        time.sleep(0.5)
        xml = dump()
        nodes = [n for n in parse_nodes(xml) if 'EditText' in n['cls']]
        if all(not n['text'] for n in nodes):
            return True
    print('CLEAR-FIELD: could not verify empty field'); sys.stdout.flush()
    return False

def clear_field():
    # CTRL+A then DEL, plus MOVE_HOME + forward/backward DEL sweeps as fallback.
    adb('shell', 'input', 'keyevent', '--keycomb', '113', '29')
    time.sleep(0.3)
    adb('shell', 'input', 'keyevent', '67')
    adb('shell', 'input', 'keyevent', '123')   # MOVE_HOME
    for _ in range(60):
        adb('shell', 'input', 'keyevent', '112')  # FORWARD_DEL
    for _ in range(60):
        adb('shell', 'input', 'keyevent', '67')   # DEL (backward)

def set_field(label_prefix, value):
    # Type with verification; never prints the value or field contents.
    for attempt in range(2):
        if not tap_field_below(label_prefix):
            return False
        time.sleep(1)
        clear_field()
        time.sleep(0.5)
        type_text(value)
        time.sleep(1)
        got = field_text_below(label_prefix)
        # Password fields may expose only mask bullets to uiautomator.
        masked_ok = got is not None and len(got) == len(value) and set(got) <= {'\u2022', '*', '.'}
        if got == value or masked_ok:
            print('field set+verified:', label_prefix, '(len', len(value), ')'); sys.stdout.flush()
            return True
        print('field MISMATCH after attempt', attempt + 1, 'for', label_prefix,
              '- got len', -1 if got is None else len(got), 'want', len(value)); sys.stdout.flush()
    return False

def keyboard_shown():
    r = adb('shell', 'dumpsys', 'input_method')
    out = r.stdout or ''
    return 'mInputShown=true' in out or 'mImeWindowVis=1' in out

def dismiss_keyboard():
    # BACK collapses the IME without navigating when a keyboard is showing.
    # When no keyboard is showing, BACK navigates back and can finish the
    # single-activity app - so only press it when the IME is actually up.
    if not keyboard_shown():
        print('dismiss_keyboard: no IME showing, skipping BACK'); sys.stdout.flush()
        return
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

def ensure_app_visible():
    for _ in range(4):
        r = adb('shell', 'dumpsys', 'window', 'displays')
        if 'com.max.neuraldimension' in (r.stdout or ''):
            return True
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
    print('RECOVER: app not visible after BACK x4'); sys.stdout.flush()
    return False

def close_dropdown_if_open():
    xml = dump()
    # an open conversation dropdown lists stored conversation titles over the chat screen
    if 'Migration seed note' in xml or ('New conversation' in xml and 'Message' not in xml):
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)

def tap_nav(label, timeout=12):
    close_dropdown_if_open()
    # Bottom-nav tap that survives an open keyboard and ANR dialogs.
    if tap_text(label, timeout=3, exact=True):
        return True
    dismiss_keyboard()
    dismiss_anr()
    return tap_text(label, timeout=timeout, exact=True)

def tap_edittext(timeout=8):
    # Tap the first EditText on screen (dialogs with a single field).
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = parse_nodes(dump())
        edits = [n for n in nodes if 'EditText' in n['cls'] and n['b'] != (0, 0, 0, 0)]
        if edits:
            e = edits[0]
            tap((e['b'][0] + e['b'][2]) // 2, (e['b'][1] + e['b'][3]) // 2)
            print('tapped EditText'); sys.stdout.flush()
            return True
        time.sleep(1)
    print('MISS EditText'); sys.stdout.flush()
    return False

def settle(timeout=90):
    """Ride out boot/launcher ANR storms: tap Wait until dialogs stay gone."""
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
    print('SETtle: ANR storm outlasted budget'); sys.stdout.flush()
    return False

def env_blocked(where):
    shot('env-blocked-' + where)
    print('ENV-BLOCKED at ' + where + ': emulator ANR storm outlasted settle budget; aborting to avoid vacuous checks')
    sys.stdout.flush()
    sys.exit(1)

def relaunch():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(10)
    if not settle(90):
        env_blocked('relaunch')

def list_app_files():
    r = adb('shell', 'run-as', PKG, 'ls', 'files')
    return (r.stdout or '').strip()

def assert_true(label, ok):
    print(('PASS ' if ok else 'FAIL ') + label); sys.stdout.flush()
    return ok

def assert_text(t, xml):
    ok = t.lower() in xml.lower()
    print(('PASS ' if ok else 'FAIL ') + 'expect text: ' + t); sys.stdout.flush()
    return ok

if not settle(120):   # let a fresh emulator finish boot jank before any driving
    env_blocked('start')

OLD_APK = os.environ.get('ND_OLD_APK', '/tmp/old.apk')
NEW_APK = os.environ.get('ND_NEW_APK', 'app/build/outputs/apk/debug/NeuralDimension-2.0.apk')

if want('migration'):
    # ---------- Room 1->2 migration evidence: seed with the RELEASED app, upgrade in place ----------
    if os.path.exists(OLD_APK):
        print('MIGRATION: installing previous release APK'); sys.stdout.flush()
        adb('shell', 'pm', 'uninstall', PKG)
        r = adb('install', OLD_APK)
        print('old install rc:', r.returncode, (r.stdout or '').strip()[-60:]); sys.stdout.flush()
        relaunch()
        tap_nav('Chat')
        time.sleep(3)
        if tap_text('Message', timeout=8):
            time.sleep(1)
            type_text('Migration seed note')
            time.sleep(1)
            tap_text('Send', timeout=3)
        time.sleep(6)
        dismiss_keyboard()
        shot('m1-old-seed')
        r = adb('install', '-r', '-d', NEW_APK)
        print('upgrade install rc:', r.returncode, (r.stdout or '').strip()[-60:]); sys.stdout.flush()
        relaunch()
        tap_nav('Chat')
        time.sleep(3)
        tap_text('New conversation', timeout=8)  # header picker
        time.sleep(2)
        tap_text('Migration seed note', timeout=8)
        time.sleep(2)
        xml = dump()
        assert_true('migration preserved seeded message', 'Migration seed note' in xml)
        shot('m2-after-upgrade')
    else:
        print('MIGRATION: old apk not present, skipped'); sys.stdout.flush()

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
    # Seed backend config via the CI-only intent hook (ND_TEST_HOOK build).
    # Values come from GitHub Actions secrets -> env -> process argv on the
    # ephemeral runner; never printed, never in repo/APK/artifacts.
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'am', 'start', '-n', PKG + '/.MainActivity',
        '--es', 'nd_backend_url', "'" + BACKEND_URL + "'",
        '--es', 'nd_app_token', "'" + APP_TOKEN + "'")
    time.sleep(8)
    tap_nav('Settings')
    time.sleep(3)
    for _ in range(5):
        if tap_text('Test connection', timeout=3):
            break
        swipe_up()
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
sent = False
for attempt in range(2):
    if not tap_text('Message', timeout=6):
        continue
    time.sleep(1)
    type_text('Hello MAX')
    time.sleep(1)
    # verify the text actually landed in the field before sending
    nodes = parse_nodes(dump())
    edits = [n for n in nodes if 'EditText' in n['cls']]
    got = edits[-1]['text'] if edits else ''
    print('chat field len after typing:', len(got)); sys.stdout.flush()
    if 'Hello MAX' in got:
        if tap_text('Send', timeout=3):
            sent = True
            break
    else:
        print('chat field empty/mismatch - retry attempt', attempt + 1); sys.stdout.flush()
        dismiss_keyboard()
if not sent:
    print('CHAT SEND FAILED'); sys.stdout.flush()
time.sleep(4)
if BACKEND_TEST:
    # SMOKE GATE (fail-fast): environment + mock connectivity + one visible MOCK
    # reply before any section runs. Abort early and cheap on any failure.
    tap_nav('Chat')
    time.sleep(2)
    tap_text('New chat', timeout=8)
    time.sleep(1)
    dismiss_keyboard()
    if not tap_text('Message', timeout=15):
        shot('smoke-fail-nofield')
        print('SMOKE-FAIL: chat field missing'); sys.stdout.flush()
        sys.exit(1)
    type_text('smoke ping')
    time.sleep(1)
    tap_text('Send', timeout=6)
    _smoke_ok = False
    _dl = time.time() + 45
    while time.time() < _dl:
        time.sleep(3)
        if 'MOCK' in dump():
            _smoke_ok = True
            break
    if not _smoke_ok:
        shot('smoke-fail-noreply')
        print('SMOKE-FAIL: no visible MOCK reply within 45s - aborting before sections'); sys.stdout.flush()
        sys.exit(1)
    print('SMOKE ok: mock reply visible'); sys.stdout.flush()

if BACKEND_TEST and want('core'):
    # Provider free tier occasionally returns 503 "high demand"; retry with
    # fresh messages until a real reply renders (max 3 extra attempts).
    for attempt in range(4):
        time.sleep(22)
        shot('04b-chat-keyboard-open')
        with open('shots/chat_dump_kbopen.xml', 'w') as f:
            f.write(dump('/tmp/ui_kbopen.xml'))
        dismiss_keyboard()
        time.sleep(1)
        shot('04-chat-reply')
        xml = dump()
        with open('shots/chat_dump.xml', 'w') as f:
            f.write(xml)
        print('chat has user bubble:', 'Hello MAX' in xml)
        print('chat has setup card:', 'SETUP' in xml or 'not connected yet' in xml)
        print('chat has error:', 'Request failed' in xml)
        print('chat screen after send:', 'BACKEND-REPLY-CHECK')
        if 'Request failed' not in xml:
            break
        if attempt == 3:
            print('CHAT REPLY STILL FAILING after retries')
            break
        time.sleep(20)
        if tap_text('Message', timeout=6):
            time.sleep(1)
            type_text('Say MAX online')
            time.sleep(1)
            tap_text('Send', timeout=3)
elif not BACKEND_TEST:
    shot('04-chat-setup-honest')
    xml = dump()
    assert_text('Secure backend is not connected yet', xml)

# ---------- Phase 2 features: MOCK backend (zero provider calls), every section independent ----------
# Live-provider success stays separately gated on quota recovery; this run verifies
# app-side logic deterministically. Mock replies are visibly prefixed "MOCK".
import re as _re
PICKER = {'ok': True}

def wait_gone(t, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if t.lower() not in dump().lower():
            return True
        time.sleep(1)
    return False

def has_exact_node(xml, text):
    return any(n['text'] == text for n in parse_nodes(xml))

def type_and_send(text):
    dismiss_keyboard()
    if tap_text('Message', timeout=10):
        time.sleep(1)
        type_text(text)
        time.sleep(1)
        tap_text('Send', timeout=5)

def poll_reply(ok_fn, budget=60):
    deadline = time.time() + budget
    while time.time() < deadline:
        time.sleep(4)
        xml = dump()
        if ok_fn(xml):
            return True, xml
        if 'Request failed' in xml:
            return False, xml
    return False, dump()

def fresh_conversation():
    ensure_app_visible()
    tap_nav('Chat')
    time.sleep(2)
    tap_text('New chat', timeout=8)
    time.sleep(1)

def long_press_text(t, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for n in parse_nodes(dump()):
            for label in (n['text'], n['desc']):
                if label and t.lower() in label.lower():
                    x = (n['b'][0] + n['b'][2]) // 2
                    y = (n['b'][1] + n['b'][3]) // 2
                    adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '900')
                    print('long-pressed', label); sys.stdout.flush()
                    return True
        time.sleep(1)
    return False

def in_file_preview():
    xml = dump()
    return 'Lens' in xml and 'Share' in xml

def attach_file(fname, timeout=15):
    if not tap_text('Attach image, PDF or text file', timeout=12):
        PICKER['ok'] = False
        print('PICKER-MISS: attach button not found'); sys.stdout.flush()
        shot('p2-picker-miss-button')
        ensure_app_visible()
        time.sleep(1)
        return False
    time.sleep(4)
    # DocumentsUI on this image previews images on tap; long-press selects instead
    if not long_press_text(fname, timeout=timeout):
        PICKER['ok'] = False
        print('PICKER-MISS: ' + fname + ' not found in DocumentsUI'); sys.stdout.flush()
        shot('p2-picker-miss-' + fname)
        ensure_app_visible()
        time.sleep(1)
        return False
    time.sleep(2)
    if in_file_preview():
        adb('shell', 'input', 'keyevent', '4')   # out of preview back to the grid
        time.sleep(2)
        long_press_text(fname, timeout=6)
        time.sleep(2)
    if not tap_text('Select', exact=True, timeout=6):
        PICKER['ok'] = False
        print('PICKER-MISS: no Select control after long-press'); sys.stdout.flush()
        shot('p2-picker-miss-select')
        ensure_app_visible()
        time.sleep(1)
        return False
    time.sleep(3)
    return True

import base64 as _b64
_png = _b64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
with open('/tmp/nd_test_red.png', 'wb') as f:
    f.write(_png)
with open('/tmp/nd_test_big.png', 'wb') as f:
    f.write(_png)
    f.write(b'\0' * 8_500_000)   # 8.5MB > 7,000,000-byte limit in code

if BACKEND_TEST:
    for fn in ('nd_test_red.png', 'nd_test_big.png'):
        adb('push', '/tmp/' + fn, '/sdcard/Download/' + fn)
        adb('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file:///sdcard/Download/' + fn)
    time.sleep(3)

    if want('regen'):
        # === Section 1: REGENERATE - reply replaced by provably different content ===
        fresh_conversation()
        regen_ok = False
        type_and_send('Reply with a random 8 digit number only.')
        ok, xml = poll_reply(lambda x: len(_re.findall(r'\d{8}', x)) > 0 and 'Thinking' not in x)
        nums = _re.findall(r'\d{8}', xml)
        if ok and nums:
            r_prev = nums[-1]
            if tap_text('Regenerate', timeout=10):
                ok2, xml2 = poll_reply(lambda x: len(_re.findall(r'\d{8}', x)) > 0 and 'Thinking' not in x)
                nums2 = _re.findall(r'\d{8}', xml2)
                regen_ok = ok2 and len(nums2) > 0 and nums2[-1] != r_prev
        assert_true('regenerate replaced reply with new content (mock)', regen_ok)
        shot('p2-regenerate')

    if want('rename'):
        # === Section 2: RENAME + SEARCH (no AI needed) ===
        fresh_conversation()
        type_and_send('Search anchor phrase zebra')
        time.sleep(4)   # user message stores immediately; reply not needed here
        dismiss_keyboard()
        if tap_text('Rename chat', timeout=10):
            time.sleep(1)
            if tap_edittext():
                time.sleep(0.5)
                clear_field_verified()
                time.sleep(0.5)
                type_text('Renamed chat')
                time.sleep(1)
                tap_text('Save', timeout=10)
            assert_true('rename dialog closed', wait_gone('Rename conversation', timeout=12))
        time.sleep(1)
        xml = dump()
        assert_text('Renamed chat', xml)
        shot('p2-rename')
        if tap_text('Search history', timeout=8):
            time.sleep(1)
            if tap_edittext():
                time.sleep(0.5)
                type_text('zebra')
                time.sleep(3)
        xml = dump()
        assert_text('Open conversation', xml)
        shot('p2-search')
        dismiss_keyboard()
        tap_text('Close', exact=True, timeout=5)
        wait_gone('Search history', timeout=8)

    if want('edit'):
        # === Section 3: EDIT - MANGO fully replaced by PAPAYA proves truncation + new reply ===
        fresh_conversation()
        edit_ok = False
        type_and_send('Reply with the word MANGO only.')
        ok, xml = poll_reply(lambda x: x.lower().count('mango') >= 2 and 'Thinking' not in x)
        if ok and tap_text('Edit', timeout=10):
            time.sleep(1)
            if tap_edittext():
                time.sleep(0.5)
                clear_field_verified()
                time.sleep(0.5)
                type_text('Reply with the word PAPAYA only.')
                time.sleep(1)
                tap_text('Save & resend', timeout=12)
            if not wait_gone('Save & resend', timeout=12):
                shot('p2-edit-dialog-stuck')
                print('EDIT: dialog did not close'); sys.stdout.flush()
            else:
                ok2, xml2 = poll_reply(lambda x: x.lower().count('papaya') >= 2 and 'Thinking' not in x)
                if ok2:
                    edit_ok = True
                    assert_true('old user text edited away', 'mango' not in xml2.lower())
                    assert_true('edited user text stored', has_exact_node(xml2, 'Reply with the word PAPAYA only.'))
        assert_true('edit resend produced reply to new text (mock)', edit_ok)
        shot('p2-edit')

    if want('attachments'):
        # === Section 4: ATTACHMENTS - chip, send, answer names the color; oversize error ===
        fresh_conversation()
        filesA = set(list_app_files().split())
        conv1_files = set()
        attach_ok = False
        if attach_file('nd_test_red.png'):
            xml = dump()
            assert_text('nd_test_red.png', xml)   # pending chip
            type_and_send('What color is this image? One word.')
            attach_ok, xml = poll_reply(lambda x: 'mock red' in x.lower())
            assert_true('attachment answer names the color (mock)', attach_ok)
            shot('p2-attachment')
        filesB = set(list_app_files().split())
        conv1_files = filesB - filesA
        if PICKER['ok']:
            assert_true('conv1 attachment file on disk', len(conv1_files) >= 1)
        if attach_file('nd_test_big.png'):
            _tl_ok = False
            _dl = time.time() + 20
            while time.time() < _dl:
                xml = dump()
                if 'File too large (about 7 MB max.)' in xml:
                    _tl_ok = True
                    break
                time.sleep(1)
            assert_true('oversize error text shown', _tl_ok)
            shot('p2-attach-toolarge')
            assert_true('oversize file not copied to storage', set(list_app_files().split()) == filesB)

    if want('conv2'):
        # === Section 5: second stored conversation (preservation reference) ===
        fresh_conversation()
        if PICKER['ok'] and attach_file('nd_test_red.png'):
            type_and_send('Hi')
            time.sleep(20)
            dismiss_keyboard()
        filesC = set(list_app_files().split())
        conv2_files = filesC - filesB
        if PICKER['ok']:
            assert_true('conv2 attachment file on disk', len(conv2_files) >= 1)

    if want('incognito'):
        # === Section 6: INCOGNITO - banner copy, zero disk writes, force-stop non-persistence ===
        fresh_conversation()
        tap_text('Incognito', timeout=8)
        time.sleep(2)
        xml = dump()
        assert_text('Incognito: not saved to your chat history', xml)   # also proves mode is ON
        assert_text('Replies still go through your secure backend.', xml)
        shot('p2-incognito-banner')
        if PICKER['ok']:
            attach_file('nd_test_red.png')
        type_and_send('Secret note test')
        time.sleep(20)
        dismiss_keyboard()
        xml = dump()
        assert_true('incognito message visible during session', has_exact_node(xml, 'Secret note test'))
        shot('p2-incognito-on')
        filesD = set(list_app_files().split())
        if PICKER['ok']:
            assert_true('incognito attachment never written to disk', len(filesD - filesC) == 0)
        relaunch()   # force-stop + cold start WHILE incognito active
        tap_nav('Chat')
        time.sleep(3)
        xml = dump()
        assert_true('incognito text gone after force-stop restart', 'Secret note test' not in xml)
        tap_text('Conversations', timeout=8)
        time.sleep(2)
        xml = dump()
        assert_true('no incognito conversation in picker after restart', 'Secret note test' not in xml)
        adb('shell', 'input', 'keyevent', '4')
        time.sleep(1)
        filesE = set(list_app_files().split())
        if PICKER['ok']:
            assert_true('no new files after incognito restart', len(filesE - filesC) == 0)
            assert_true('normal conversation attachment preserved', len(conv2_files & filesE) == len(conv2_files) and len(conv2_files) > 0)
        shot('p2-incognito-after-restart')

    if want('delete'):
        # === Section 7: delete conv1 -> only its attachment file removed ===
        tap_text('Conversations', timeout=8)
        time.sleep(2)
        if not tap_text('What color is this image', timeout=8):  # conv1 title (its first message text)
            adb('shell', 'input', 'keyevent', '4')
            time.sleep(1)
        time.sleep(1)
        if tap_text('Delete chat', timeout=6):
            time.sleep(3)
        filesF = set(list_app_files().split())
        if PICKER['ok']:
            assert_true('deleted conversation attachment removed', len(conv1_files & filesF) == 0)
            assert_true('other conversation attachment preserved after delete', len(conv2_files & filesF) == len(conv2_files))
        shot('p2-delete-hygiene')

    if want('errors'):
        # === Section 8: error rendering - concise quota message + expandable detail (deterministic trigger) ===
        fresh_conversation()
        type_and_send('TRIGGER429 test')
        ok, xml = poll_reply(lambda x: 'rate or quota limit' in x.lower())
        assert_true('concise quota error shown', ok)
        assert_true('no fixed retry-time promised', 'minute' not in xml.lower())
        assert_true('raw JSON not dumped on screen', 'exceeded your current quota' not in xml)
        if ok and tap_text('Technical details', timeout=6):
            time.sleep(2)
            xml = dump()
            assert_text('gemini 429', xml)   # raw detail behind expansion
        shot('p2-error-rendering')

    print('PHASE2-COVERAGE picker=' + ('ok' if PICKER['ok'] else 'MISS'))
    sys.stdout.flush()

if want('tail'):

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
        speaking_seen = False
        for _ in range(10):
            xml = dump()
            if 'SPEAKING' in xml:
                speaking_seen = True
                break
            time.sleep(0.5)
        print(('PASS ' if speaking_seen else 'FAIL ') + 'SPEAKING state observed'); sys.stdout.flush()
        shot('06-voice-speaking')
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
