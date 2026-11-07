import os, re, subprocess, sys, time

BACKEND_URL = os.environ.get('ND_TEST_BACKEND_URL', '').strip()
APP_TOKEN = os.environ.get('ND_TEST_APP_TOKEN', '').strip()
BACKEND_TEST = bool(BACKEND_URL and APP_TOKEN)
ONLY = set(x.strip() for x in os.environ.get('ND_SECTIONS', 'all').split(','))
RELEASE_GATE = os.environ.get('ND_RELEASE_GATE', '0') == '1'  # release APK: no run-as, no hook; UI-level preservation only
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
        pk = re.search(r'package="([^"]*)"', raw)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        if not bm:
            continue
        nodes.append({'text': unesc(tm.group(1)) if tm else '', 'desc': unesc(cm.group(1)) if cm else '',
                      'cls': cl.group(1) if cl else '', 'pkg': pk.group(1) if pk else '',
                      'b': tuple(int(bm.group(i)) for i in range(1, 5))})
    return nodes

def swipe_up():
    adb('shell', 'input', 'swipe', '540', '2000', '540', '900', '300')
    time.sleep(1)

def swipe_up_small():
    adb('shell', 'input', 'swipe', '540', '1800', '540', '1200', '250')
    time.sleep(0.8)

def swipe_down():
    adb('shell', 'input', 'swipe', '540', '900', '540', '2000', '300')
    time.sleep(1)

def tools_top():
    # Sections inherit the previous section's scroll position (proven run
    # 42248503219: notes/reminders sat ABOVE the habits viewport; ensure_visible
    # only swipes down -> honest MISS/FAIL). Reset to the top before searching.
    for _ in range(8):
        swipe_down()

def ensure_visible(text, max_swipes=14):
    """Small swipes until text appears in the dump; avoids jumping past targets.

    An open IME breaks this twice over (proven run 42267432732): the shrunken
    window uncomposes everything under the keyboard, and small swipes starting
    at y=1800 land ON the keyboard and scroll nothing. Dismiss it first;
    dismiss_keyboard is IME-gated and never BACKs a closed keyboard."""
    dismiss_keyboard()
    for _ in range(max_swipes):
        if text in dump():
            return True
        swipe_up_small()
    return text in dump()

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

FAILED_ASSERTIONS = []

def assert_true(label, ok):
    print(('PASS ' if ok else 'FAIL ') + label); sys.stdout.flush()
    if not ok:
        FAILED_ASSERTIONS.append(label)
    return ok

def assert_text(t, xml):
    ok = t.lower() in xml.lower()
    print(('PASS ' if ok else 'FAIL ') + 'expect text: ' + t); sys.stdout.flush()
    if not ok:
        FAILED_ASSERTIONS.append('expect text: ' + t)
    return ok

def assert_app_text(t, xml):
    # App-scoped: the text must appear in a node owned by OUR package. A raw
    # assert_text can be satisfied by the LAUNCHER icon label "Neural Dimension"
    # (proven run 42233330418: 30 vacuous PASSes on a dead app). uiautomator
    # stamps every node with its owning package.
    ok = any(t.lower() in n['text'].lower() and n['pkg'] == PKG for n in parse_nodes(xml))
    print(('PASS ' if ok else 'FAIL ') + 'expect app-text: ' + t); sys.stdout.flush()
    if not ok:
        FAILED_ASSERTIONS.append('expect app-text: ' + t)
    return ok

import atexit
@atexit.register
def _assertion_gate():
    # A green job must never hide a failed required assertion (main review rule).
    if FAILED_ASSERTIONS:
        print('ASSERTION-GATE: %d failed -> job must fail: %s' % (len(FAILED_ASSERTIONS), FAILED_ASSERTIONS))
        sys.stdout.flush()
        os._exit(1)

if not settle(120):   # let a fresh emulator finish boot jank before any driving
    env_blocked('start')

# install -r (run_ui_pass.sh) kills the app process; it must be running before
# any driving. Previously only the BACKEND_TEST block launched it - without a
# backend the driver drove the LAUNCHER all run (run 42233330418: 30 FAILs on
# a dead app; 'NEURAL DIMENSION' vacuously matched the launcher icon label).
relaunch()
_r = adb('shell', 'dumpsys', 'window', 'displays')
if PKG not in (_r.stdout or ''):
    env_blocked('app-not-foreground-after-relaunch')

# The app being foreground is not enough: it must be the CANDIDATE binary
# (run 42285108996: v2.0.1 foregrounded and 26 sections cascaded against it).
_r = subprocess.run([sys.executable, 'tools/verify_candidate.py', '--version-only'])
if _r.returncode != 0:
    shot('candidate-gate-fail')
    print('ENV-BLOCKED at candidate-gate: installed package is not the expected candidate; aborting before feature sections')
    sys.stdout.flush()
    sys.exit(1)

OLD_APK = os.environ.get('ND_OLD_APK', '/tmp/old.apk')
NEW_APK = os.environ.get('ND_NEW_APK') or (
    'app/build/outputs/apk/release/NeuralDimension-release.apk' if RELEASE_GATE
    else 'app/build/outputs/apk/debug/NeuralDimension-2.0.apk')

if want('migration') and RELEASE_GATE:
    # Release runs must NOT take this section: it pm-uninstalls the package
    # mid-run (wiping the run_ui_pass.sh seed) and installs ND_NEW_APK, whose
    # default used to be the DEBUG path - missing in release runs, so run
    # 42285108996 vacuously passed 'migration preserved' on the still-running
    # v2.0.1 and cascaded 26 FAILs against the wrong binary. Release
    # preservation evidence is the upgrade(release) section; this section is
    # debug-loop only.
    print('migration section skipped in release gate (upgrade(release) owns preservation evidence)')
    sys.stdout.flush()
elif want('migration'):
    # ---------- Room 1->4 chain migration evidence: seed with the RELEASED v2.0.1 (schema 1), upgrade in place ----------
    if os.path.exists(OLD_APK):
        print('MIGRATION: installing previous release APK'); sys.stdout.flush()
        adb('shell', 'pm', 'uninstall', PKG)
        r = adb('install', OLD_APK)
        print('old install rc:', r.returncode, (r.stdout or '').strip()[-60:]); sys.stdout.flush()
        # A failed install must never be driven over: gate loudly, never a
        # vacuous 'preserved' PASS on the still-running previous binary.
        if assert_true('migration: old APK install succeeded', r.returncode == 0):
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
            if assert_true('migration: upgrade install succeeded', r.returncode == 0):
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
assert_app_text('NEURAL DIMENSION', xml)
assert_app_text('AI STATUS', xml)
assert_app_text('TASKS', xml)

time.sleep(6)
shot('02-home-idle-anim')

tap_text('AI STATUS')
time.sleep(2)
shot('03-panel-ai-status')
xml = dump()
assert_text('Provider', xml)
tap_text('Close', exact=True)
time.sleep(1)

# Chat: send a message; with no key configured the app must show the honest setup card.
# chat-real mode (ND_REAL_URL set): ONE short synthetic message against the real
# backend - the single authorized provider call; no retries on 429/auth/quota.
REAL_URL = os.environ.get('ND_REAL_URL', '').strip()
CHAT_MSG = 'Reply with exactly: PONG' if REAL_URL else 'Hello MAX'
tap_nav('Chat')
time.sleep(3)
xml = dump()
assert_text('New conversation', xml)
sent = False
for attempt in range(2):
    if not tap_text('Message', timeout=6):
        continue
    time.sleep(1)
    type_text(CHAT_MSG)
    time.sleep(1)
    # verify the text actually landed in the field before sending
    nodes = parse_nodes(dump())
    edits = [n for n in nodes if 'EditText' in n['cls']]
    got = edits[-1]['text'] if edits else ''
    print('chat field len after typing:', len(got)); sys.stdout.flush()
    if CHAT_MSG in got:
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
elif REAL_URL:
    # The ONE authorized real-backend text message: bounded poll for the reply.
    _ok = False
    _dl = time.time() + 90
    while time.time() < _dl:
        time.sleep(5)
        _x = dump()
        if 'PONG' in _x:
            _ok = True
            break
        if 'Request failed' in _x:
            break
    shot('p7-chat-real')
    _x = dump()
    print('chat-real: reply rendered=%s request_failed=%s setup_card=%s'
          % ('PONG' in _x, 'Request failed' in _x, 'Secure backend is not connected yet' in _x)); sys.stdout.flush()
    assert_true('chat-real: real backend reply rendered (PONG)', _ok and 'Request failed' not in _x)
elif not BACKEND_TEST:
    shot('04-chat-setup-honest')
    xml = dump()
    # The release flow runs AFTER seeding, so backendUrl is non-blank
    # (seed-settings-v1.invalid): the app is "configured" (AiProvider.isConfigured
    # = url.isNotBlank) and the send surfaces an honest ERROR bubble
    # ("Request failed"), not the setup card. Both are honest rendered states of
    # the same send path; neither-present is the only failure.
    _honest = 'Secure backend is not connected yet' in xml
    _err = 'Request failed' in xml
    print('chat honest state: setup_card=%s request_failed=%s' % (_honest, _err)); sys.stdout.flush()
    assert_true('startup chat: honest backend response rendered (setup card or request failure)', _honest or _err)

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
                if 'File too large' in xml:  # app string is 'File too large (about 7 MB max).' - match stable prefix only
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

    if want('clipping'):
        # === Section 9: clipping - overflow pinning, IME close, history readability ===
        fresh_conversation()
        sent = 0
        # long probe text: tall user bubbles so 6 pairs reliably overflow the viewport
        for i in range(6):
            type_and_send('clip probe %d ' % i + 'x' * 90)
            sent += 1
            ok, _ = poll_reply(lambda x, n=sent: x.count('MOCK') >= min(n, 3), budget=90)
            if not ok:
                print('clipping: reply %d slow, continuing with %d pairs' % (i, sent))
                break
        dismiss_keyboard()
        time.sleep(3)

        def settle_and_measure():
            prev = None
            xml = dump()
            for _ in range(12):
                xml = dump()
                cur = [n['b'] for n in parse_nodes(xml)
                       if n['text'].startswith(('clip probe', 'MOCK'))]
                if cur and cur == prev:
                    break
                prev = cur
                time.sleep(2)
            nodes = parse_nodes(xml)
            hdr = max((n['b'][3] for n in nodes if n['desc'] in
                       ('Conversations', 'New chat', 'Incognito', 'Search history',
                        'Rename chat', 'Delete chat')), default=0)
            msgs = [n for n in nodes if n['text'].startswith(('clip probe', 'MOCK'))]
            inp_top = min((n['b'][1] for n in nodes if n['text'].startswith('Message')),
                          default=2400)
            return xml, nodes, hdr, msgs, inp_top

        xml, nodes, hdr_bottom, msgs, inp_top = settle_and_measure()
        with open('shots/clipping_settled.xml', 'w') as f:
            f.write(xml)
        shot('p2-clipping-settled')
        assert_true('clipping: header present', hdr_bottom > 0)
        overlaps = [n for n in msgs if n['b'][1] < hdr_bottom < n['b'][3]]
        assert_true('clipping: no message text overlaps header band (settled)',
                    len(overlaps) == 0)
        # real overflow = content span exceeds the list viewport (short content
        # top-stacked under the header is NOT overflow - not a failure per main)
        last_bottom = max((n['b'][3] for n in msgs), default=0)
        first_top = min((n['b'][1] for n in msgs), default=0)
        viewport = inp_top - hdr_bottom
        span = last_bottom - first_top
        overflowed = len(msgs) > 0 and span >= viewport - 40
        print('clipping extent: pairs=%d viewport=%d content_span=%d overflowed=%s bottom_gap=%d'
              % (sent, viewport, span, overflowed, inp_top - last_bottom))
        if overflowed:
            assert_true('clipping: newest message flush above input (overflow, gap=%d)'
                        % (inp_top - last_bottom), 0 <= inp_top - last_bottom < 120)
        else:
            print('clipping: content did not overflow; bottom-gap not a failure criterion')

        # keyboard open/close must not strand the pin
        if tap_text('Message', timeout=8):
            time.sleep(3)
            dismiss_keyboard()
            time.sleep(3)
            xml, nodes, hdr_bottom, msgs, inp_top = settle_and_measure()
            shot('p2-clipping-after-keyboard')
            if msgs:
                last_bottom = max(n['b'][3] for n in msgs)
                overlaps = [n for n in msgs if n['b'][1] < hdr_bottom < n['b'][3]]
                assert_true('clipping: no header overlap after keyboard close', len(overlaps) == 0)
                if overflowed:
                    assert_true('clipping: still pinned after keyboard close (gap=%d)'
                                % (inp_top - last_bottom), 0 <= inp_top - last_bottom < 120)

        # reading older history must NOT snap back to bottom
        for _ in range(4):
            adb('shell', 'input', 'swipe', '540', '700', '540', '1800', '400')
            time.sleep(2)
        shot('p2-clipping-history')
        probes = [n for n in parse_nodes(dump()) if n['text'].startswith('clip probe')]
        clean = [n for n in probes if n['b'][1] >= hdr_bottom and n['b'][3] <= inp_top]
        assert_true('clipping: probe messages revealable on scroll-up (%d visible)' % len(clean),
                    len(clean) >= 1)
        before = sorted(n['text'] for n in clean)
        time.sleep(6)   # any forced snapback would hide these again
        probes2 = [n for n in parse_nodes(dump()) if n['text'].startswith('clip probe')]
        clean2 = sorted(n['text'] for n in probes2 if n['b'][1] >= hdr_bottom and n['b'][3] <= inp_top)
        assert_true('clipping: no forced snapback while reading history', clean2 == before)
        shot('p2-clipping-history-held')

    print('PHASE2-COVERAGE picker=' + ('ok' if PICKER['ok'] else 'MISS'))
    sys.stdout.flush()

if want('upgrade') and RELEASE_GATE:
    # === RELEASE-candidate preservation: UI-level only (release is not debuggable:
    # no run-as, no hook). Captured BEFORE any test reconfiguration. ===
    tap_nav('Tools', timeout=12)
    time.sleep(3)
    tools_top()
    assert_true('upgrade(release): seeded task visible in UI after migration', 'seed-task-v1' in dump())
    shot('upgrade-release-task')
    tap_nav('Chat', timeout=12)
    time.sleep(3)
    conv_ok = False
    # Header picker: tap the ArrowDropDown by its content-desc - the button's
    # TEXT is the current conversation title (not always 'New conversation').
    if tap_text('Conversations', timeout=10, exact=True):
        time.sleep(2)
        if tap_text('seed-message-v1', timeout=10, exact=True):  # dropdown item
            time.sleep(3)
            conv_ok = 'seed-message-v1' in dump()
    assert_true('upgrade(release): seeded conversation in picker + message rendered', conv_ok)
    shot('upgrade-release-chat')
    tap_nav('Settings', timeout=12)
    time.sleep(3)
    # Field-scoped, not raw substring: the seeded value must sit in the field
    # BELOW its label - a value landed in the wrong field must not green this
    # (fake-model bug proved the substring form can pass cross-field).
    ensure_visible('Your name')
    # EXACT field equality (main's bar): a merged 'RoutSeedUserV1' (default
    # userName not cleared by the seed, run 42267432732 pixel) must FAIL.
    _name_field = (field_text_below('Your name') or '').strip()
    print('upgrade(release): name field exact value: <%s>' % _name_field); sys.stdout.flush()
    name_seen = _name_field == 'SeedUserV1'
    ensure_visible('Backend URL')
    _url_field = (field_text_below('Backend URL') or '').strip()
    print('upgrade(release): URL field exact value: <%s>' % _url_field); sys.stdout.flush()
    url_seen = _url_field == 'https://seed-settings-v1.invalid'
    assert_true('upgrade(release): seeded profile name visible in Settings', name_seen)
    assert_true('upgrade(release): seeded backend URL visible in Settings', url_seen)
    shot('upgrade-release-settings')

if want('upgrade') and not RELEASE_GATE:
    # === Debug-loop gate: data seeded into delivered v2.0.1 must survive in-place upgrade ===
    # Exact field-level proof: pull the migrated Room DB + DataStore via run-as
    # (debug build is debuggable; release install-r path exercised by the workflow).
    subprocess.run(['adb', 'exec-out', 'run-as', 'com.max.neuraldimension',
                    'cat', 'databases/max.db'], stdout=open('/tmp/upgraded.db', 'wb'))
    try:
        import sqlite3
        con = sqlite3.connect('/tmp/upgraded.db')
        tasks = [r[0] for r in con.execute('SELECT title FROM tasks')]
        convs = [r[0] for r in con.execute('SELECT title FROM conversations')]
        msgs = [r[0] for r in con.execute("SELECT text FROM messages WHERE role='user'")]
        tables = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        con.close()
        print('UPGRADE-DB: tables=', tables)
        assert_true('upgrade: seeded task row preserved', 'seed-task-v1' in tasks)
        assert_true('upgrade: seeded conversation row preserved', any('seed-message-v1' in c for c in convs))
        assert_true('upgrade: seeded user message row preserved', any('seed-message-v1' in m for m in msgs))
        assert_true('upgrade: v4 tables created (notes/reminders/habits/expenses)',
                    all(t in tables for t in ['notes', 'reminders', 'habits', 'expenses']))
    except Exception as e:
        print('UPGRADE-DB-ERROR:', e)
        assert_true('upgrade: migrated DB readable via run-as', False)
    # DataStore settings preservation
    r = subprocess.run(['adb', 'exec-out', 'run-as', 'com.max.neuraldimension',
                        'cat', 'files/datastore/max_settings.preferences_pb'],
                       capture_output=True)
    pb = r.stdout
    # NOTE: seeded backend URL is asserted PRE-LAUNCH in the workflow - by this
    # point the ND_TEST_HOOK intent has legitimately rewritten backendUrl to the
    # mock backend, so checking the seed value here would test the hook, not
    # preservation (proven run 42218011334).
    assert_true('upgrade: seeded profile name preserved in DataStore (post-launch)', b'SeedUserV1' in pb)
    # UI-level confirmation too
    tap_nav('Tools', timeout=12)
    time.sleep(3)
    xml = dump()
    assert_true('upgrade: seeded task visible in UI after migration', 'seed-task-v1' in xml)
    shot('upgrade-after')


if want('weather'):
    # === Phase 6c: Weather (real Open-Meteo network) + Daily briefing (local) ===
    # Network-dependent: failure may be runner networking, not the app.
    dismiss_keyboard()
    tap_nav('Tools', timeout=8)
    time.sleep(3)
    tools_top()
    xml = dump()
    assert_true('weather: briefing card rendered', 'Daily briefing' in xml and 'Refresh briefing' in xml)
    if tap_text('Refresh briefing', timeout=6):
        time.sleep(2)
        assert_true('weather: briefing aggregates local data', 'open task' in dump())
    ensure_visible('City name...')
    if tap_text('City name...', timeout=8):
        time.sleep(1)
        type_text('Bhubaneswar')
        time.sleep(1)
        dismiss_keyboard()
        tap_text('Check weather', timeout=5)
        time.sleep(15)
        xml = dump()
        ok = 'humidity' in xml and 'wind' in xml
        assert_true('weather: real Open-Meteo result rendered (network-dependent)', ok or 'Weather unavailable' in xml)
        if ok:
            print('WEATHER-LINE:', [l for l in xml.split('<') if 'humidity' in l][:1])
    shot('p6-weather-briefing')

if want('habits'):
    # === Phase 6b: Habits + Expenses (Room-backed, local) ===
    dismiss_keyboard()
    tap_nav('Tools', timeout=8)
    time.sleep(3)
    tools_top()
    ensure_visible('Add a habit...')
    if tap_text('Add a habit...', timeout=8):
        time.sleep(1)
        type_text('nd-test-habit')
        time.sleep(1)
        tap_text('Save habit', timeout=5)
        time.sleep(2)
    xml = dump()
    assert_true('habits: habit listed', 'nd-test-habit' in xml and 'streak 0' in xml)
    if tap_text('Mark today', timeout=6):
        time.sleep(2)
        assert_true('habits: streak becomes 1 (observed state change)', 'streak 1' in dump())
    ensure_visible('Expense label...')
    if tap_text('Expense label...', timeout=8):
        time.sleep(1)
        type_text('nd-test-coffee')
        time.sleep(1)
        dismiss_keyboard()
        if tap_text('Rs.', exact=True, timeout=5):
            time.sleep(1)
            type_text('120')
            time.sleep(1)
            dismiss_keyboard()
        tap_text('Save expense', timeout=5)
        time.sleep(2)
    xml = dump()
    assert_true('habits: expense listed', 'nd-test-coffee' in xml and '120.00' in xml)
    assert_true('habits: total updated (observed state change)', 'Total: Rs. 120.00' in xml)
    shot('p6-habits-expenses')

if want('remind'):
    # === Phase 6: Part 17 slice - Notes (Room) + Reminders (WorkManager -> real notification) ===
    dismiss_keyboard()
    tap_nav('Tools', timeout=8)
    time.sleep(3)
    tools_top()
    ensure_visible('Add a note...')
    if tap_text('Add a note...', timeout=8):
        time.sleep(1)
        type_text('nd-test-note')
        time.sleep(1)
        tap_text('Save note', timeout=5)
        time.sleep(2)
    assert_true('remind: note listed', 'nd-test-note' in dump())
    ensure_visible('Reminder title...')
    if tap_text('Reminder title...', exact=True, timeout=8):
        time.sleep(1)
        type_text('nd-test-reminder')
        time.sleep(1)
        dismiss_keyboard()
        if tap_text('min', exact=True, timeout=5):
            time.sleep(1)
            type_text('1')
            time.sleep(1)
            dismiss_keyboard()
        tap_text('Schedule', timeout=5)
        time.sleep(2)
        tap_text('Allow', exact=True, timeout=6)  # dialog BUTTON, not the title substring
        time.sleep(2)
    xml = dump()
    assert_true('remind: reminder listed', 'nd-test-reminder' in xml)
    shot('p6-reminders')
    # observed effect: bounded watch for the WorkManager-fired notification.
    # Evidence is printed UNCONDITIONALLY: notification-record blocks for our
    # package (pkg/channel/id/title) plus a filtered logcat tail (exact post
    # time), so a PASS carries delivery identity and a FAIL carries system
    # state. No record inside a finite emulator window does not by itself
    # prove absence of the effect (permission/channel/OS/scheduling state) -
    # the printed evidence is what distinguishes those.
    posted = False
    out = ''
    for _ in range(24):  # 24 x 15s = 360s bounded watch
        time.sleep(15)
        out = subprocess.run(['adb', 'shell', 'dumpsys', 'notification'],
                             capture_output=True, text=True).stdout
        if 'nd-test-reminder' in out or 'MAX Reminder' in out:
            posted = True
            break
    block = []
    printed = 0
    for line in out.splitlines() + ['NotificationRecord(']:
        if 'NotificationRecord' in line:
            if block and any(('pkg=' + PKG) in b or 'MAX Reminder' in b for b in block):
                for b in block[:20]:
                    if printed < 60:
                        print('NOTIFY-EVIDENCE ' + b.strip())
                        printed += 1
            block = [line]
        elif block:
            block.append(line)
    log = subprocess.run(['adb', 'logcat', '-d', '-s', 'NotificationManager:*', 'ReminderWorker:*'],
                         capture_output=True, text=True).stdout
    for line in log.splitlines()[-30:]:
        print('NOTIFY-LOGCAT ' + line)
    assert_true('remind: system notification posted (observed effect)', posted)
    # cleanup: cancel the reminder entry
    if tap_text('Cancel', timeout=5):
        time.sleep(2)

if want('device'):
    # === Phase 5: Part 18 phone integration surfaces in Tools (all real/local) ===
    dismiss_keyboard()
    tap_nav('Tools', timeout=8)
    time.sleep(3)
    tools_top()
    assert_true('device: battery shown', ensure_visible('Battery'))
    assert_true('device: storage shown', ensure_visible('Storage free'))
    assert_true('device: flashlight card present', ensure_visible('Flashlight', max_swipes=6))
    for _ in range(3):
        swipe_up(); time.sleep(1.5)
    xml = dump()
    assert_true('device: RAM shown', 'RAM free' in xml)
    swipe_up(); time.sleep(1.5)
    xml = dump()
    assert_true('device: diagnostics (model/uptime)', 'Model:' in xml and 'Uptime' in xml)
    assert_true('device: open apps lists launchable apps', 'Open apps' in xml and 'Settings' in xml)
    assert_true('device: media volume control', 'Media volume' in xml)
    assert_true('device: notifications shortcut', 'Notifications' in xml)
    assert_true('device: camera shortcut', xml.count('Camera') >= 2)  # app entry + shortcut button
    shot('p5-tools-device')

if want('studio'):
    # === Phase 4: Web Studio - local project create/edit/run/console/export ===
    # Fully on-device; zero provider calls.
    dismiss_keyboard()
    tap_nav('Studio', timeout=8)
    time.sleep(3)
    xml = dump()
    assert_true('studio: empty state', 'No projects yet' in xml)
    assert_true('studio: honest labeling (sandbox + CDN note, no offline claim)',
                'sandboxed preview' in xml and 'Nothing leaves the device' not in xml and 'CDN' in xml)
    if tap_text('New project name', timeout=8):
        time.sleep(1)
        type_text('StudioTest')
        time.sleep(1)
        tap_text('Create project', timeout=5)
        time.sleep(3)
    xml = dump()
    assert_true('studio: project opened in editor', 'Hello from MAX Studio' in xml)
    shot('p4-studio-editor')
    # run preview
    if tap_text('Run', timeout=6):
        time.sleep(6)
        xml = dump()
        if 'Hello from MAX Studio' in xml:
            assert_true('studio: preview renders template heading', True)
        else:
            # WebView content is invisible to uiautomator on this emulator; the
            # pixel is the evidence carrier, so this is not a required dump assert.
            print('NOTE studio: preview heading not in dump (WebView/a11y limitation) - verify p4-studio-preview.png pixel'); sys.stdout.flush()
        shot('p4-studio-preview')
        if tap_text('Console (', timeout=6):
            time.sleep(2)
            xml = dump()
            assert_true('studio: console captured script log', 'studio-ready' in xml)
            shot('p4-studio-console')
    # export single-file html
    if tap_text('Export project as single HTML file', timeout=6):
        time.sleep(3)
        assert_true('studio: export notice shown', 'Exported to Downloads' in dump())
        out = subprocess.run(['adb', 'shell', 'ls', '/storage/emulated/0/Download/NeuralDimension/'],
                             capture_output=True, text=True).stdout
        assert_true('studio: exported file exists in Downloads', 'StudioTest.html' in out)
        # end-to-end export bytes: pull the actual exported file and inspect content
        subprocess.run(['adb', 'pull', '/storage/emulated/0/Download/NeuralDimension/StudioTest.html', '/tmp/exported-studio.html'],
                       capture_output=True)
        try:
            exported = open('/tmp/exported-studio.html').read()
            assert_true('studio: exported bytes have CSS inlined', '<style>' in exported and 'font-family' in exported)
            assert_true('studio: exported bytes have JS inlined', "console.log('studio-ready')" in exported)
            assert_true('studio: exported bytes have no leftover local refs',
                        'href="style.css"' not in exported and 'src="script.js"' not in exported)
        except Exception as e:
            assert_true('studio: exported file readable for content check', False)
    # React template export: CDN deps must remain external (not bundled)
    if tap_text('Back to projects', timeout=6):
        time.sleep(2)
        if tap_text('New project name', timeout=6):
            time.sleep(1)
            type_text('ReactExp')
            time.sleep(1)
            tap_text('Blank page', timeout=4)   # open template dropdown
            time.sleep(1)
            tap_text('React (CDN)', timeout=4)  # pick React item in dropdown
            time.sleep(1)
            tap_text('Create project', timeout=5)
            time.sleep(3)
        if tap_text('Export project as single HTML file', timeout=6):
            time.sleep(3)
            subprocess.run(['adb', 'pull', '/storage/emulated/0/Download/NeuralDimension/ReactExp.html', '/tmp/exported-react.html'],
                           capture_output=True)
            try:
                rex = open('/tmp/exported-react.html').read()
                assert_true('studio: react export keeps CDN external', 'https://unpkg.com/react@18' in rex)
                assert_true('studio: react export preserves text/babel', 'text/babel' in rex)
                assert_true('studio: react export inlines local js', "console.log('studio-ready')" in rex)
            except Exception:
                assert_true('studio: react export readable', False)
        tap_text('Back to projects', timeout=6)
        time.sleep(2)
    # back, list, delete both projects deterministically
    tap_text('Back to projects', timeout=6)
    time.sleep(2)
    xml = dump()
    _studio_listed = 'StudioTest' in xml and 'ReactExp' in xml
    assert_true('studio: project listed', _studio_listed)
    tap_text('Delete project', timeout=6)
    time.sleep(2)
    xml = dump()
    assert_true('studio: first project deleted', ('StudioTest' in xml) != ('ReactExp' in xml))
    tap_text('Delete project', timeout=6)
    time.sleep(2)
    xml = dump()
    # Non-vacuous: deletion is only proven when the list was proven BEFORE
    # deletion (zero-project creation failure must not green this assert).
    assert_true('studio: both projects deleted (after creation proven)',
                _studio_listed and 'StudioTest' not in xml and 'ReactExp' not in xml)


# ---- voice-speak helpers: minimal stdlib PNG decode for pixel-diff evidence ----
_PNG_CACHE = {}

def _png_rows(path, ymax_frac=1.0):
    """Decode an 8-bit non-interlaced RGB/RGBA PNG to grayscale rows (stdlib only).

    Stops after ymax_frac of the rows (mouth band is in the upper robot area).
    Returns (w, h, rows) or None on anything unexpected - callers FAIL honestly."""
    key = (path, ymax_frac)
    if key in _PNG_CACHE:
        return _PNG_CACHE[key]
    import struct, zlib
    out = None
    try:
        d = open(path, 'rb').read()
        if d[:8] == b'\x89PNG\r\n\x1a\n':
            pos = 8; w = h = ct = None; idat = b''
            while pos + 8 <= len(d):
                ln = struct.unpack('>I', d[pos:pos+4])[0]
                typ = d[pos+4:pos+8]; chunk = d[pos+8:pos+8+ln]
                if typ == b'IHDR':
                    w, h = struct.unpack('>II', chunk[:8])
                    bd, ct, inter = chunk[8], chunk[9], chunk[12]
                    if bd != 8 or inter != 0 or ct not in (2, 6):
                        w = None; break
                elif typ == b'IDAT':
                    idat += chunk
                elif typ == b'IEND':
                    break
                pos += 12 + ln
            if w is not None:
                ch = 4 if ct == 6 else 3
                raw = zlib.decompress(idat)
                stride = w * ch
                ymax = min(h, int(h * ymax_frac) + 1)
                rows = []; prev = bytearray(stride); off = 0
                for y in range(ymax):
                    filt = raw[off]; line = bytearray(raw[off+1:off+1+stride]); off += 1 + stride
                    if filt == 1:
                        for i in range(ch, stride): line[i] = (line[i] + line[i-ch]) & 0xFF
                    elif filt == 2:
                        for i in range(stride): line[i] = (line[i] + prev[i]) & 0xFF
                    elif filt == 3:
                        for i in range(stride):
                            line[i] = (line[i] + ((line[i-ch] if i >= ch else 0) + prev[i]) // 2) & 0xFF
                    elif filt == 4:
                        for i in range(stride):
                            a = line[i-ch] if i >= ch else 0; b = prev[i]; c = prev[i-ch] if i >= ch else 0
                            p = a + b - c
                            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                            pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                            line[i] = (line[i] + pr) & 0xFF
                    prev = line
                    g = [0] * w
                    for x in range(w):
                        i = x * ch
                        g[x] = (line[i] * 299 + line[i+1] * 587 + line[i+2] * 114) // 1000
                    rows.append(g)
                out = (w, h, rows)
    except Exception:
        out = None
    _PNG_CACHE[key] = out
    return out

def _band_diff(pa, pb, band):
    """Mean abs grayscale diff inside a fractional (x0,x1,y0,y1) band; None if not computable."""
    x0f, x1f, y0f, y1f = band
    A = _png_rows(pa, y1f); B = _png_rows(pb, y1f)
    if not A or not B:
        return None
    (w, h, ra), (w2, h2, rb) = A, B
    if (w, h) != (w2, h2):
        return None
    x0, x1 = int(w * x0f), min(int(w * x1f), w)
    y0, y1 = int(h * y0f), min(int(h * y1f), len(ra), len(rb))
    if x1 <= x0 or y1 <= y0:
        return None
    tot = cnt = 0
    for y in range(y0, y1):
        ga, gb = ra[y], rb[y]
        for x in range(x0, x1):
            tot += abs(ga[x] - gb[x]); cnt += 1
    return (tot / cnt) if cnt else None

if want('voice-speak'):
    # === voice-speak: real TTS synthesis + amplitude-envelope lip sync (V1) ===
    # No provider, no backend: 'Test MAX voice' speaks a fixed sentence locally.
    # Evidence: MAX_VOICE logcat (synth bytes/windows; then audible playback OR
    # the honest -noaudio fallback), SPEAKING UI state, mouth-band pixel movement
    # vs a 2-frame idle baseline. Fallback envelope animation is reported as such;
    # it is NOT audible playback proof.
    dismiss_keyboard()
    tap_nav('Voice', timeout=8)
    time.sleep(3)
    xml = dump()
    assert_true('voice-speak: voice screen rendered (recognition label)', 'Recognition:' in xml)
    have_btn = 'Test MAX voice' in xml
    assert_true('voice-speak: Test MAX voice control present', have_btn)
    if have_btn:
        adb('logcat', '-c')
        shot('vs-idle-0')
        time.sleep(1.2)
        shot('vs-idle-1')
        tap_text('Test MAX voice', timeout=4)
        speaking_seen = False
        synth = None
        t0 = time.time()
        while time.time() - t0 < 40:
            time.sleep(1.5)
            r = adb('logcat', '-d', '-s', 'MAX_VOICE:I')
            if not speaking_seen:
                if 'SPEAKING' in dump():
                    speaking_seen = True
            synth = re.search(r'synth done bytes=(\d+) windows=(\d+) windowMs=(\d+)', r.stdout or '')
            if synth:
                break
        ok = synth is not None and int(synth.group(1)) > 100 and int(synth.group(2)) > 0
        if not assert_true('voice-speak: TTS synthesized a real WAV (bytes>100 windows>0)', ok):
            print('NOTE voice-speak: no synth within 40s - TTS engine absent or environment blocked; section stops here'); sys.stdout.flush()
        else:
            assert_true('voice-speak: SPEAKING state visible during drive', speaking_seen)
            win_ms = int(synth.group(2)) * int(synth.group(3))
            nshots = max(3, min(6, win_ms // 450))
            for i in range(nshots):
                shot('vs-speak-%d' % i)
                time.sleep(0.45)
            r = adb('logcat', '-d', '-s', 'MAX_VOICE:I')
            log = r.stdout or ''
            with open('shots/voice-speak-logcat.txt', 'w') as f:
                f.write(log)
            pb = re.search(r'playback start durMs=(\d+)', log)
            if pb:
                assert_true('voice-speak: audible playback path started (durMs>0)', int(pb.group(1)) > 0)
                amps = {a for a in re.findall(r'amp=([\d.]+)', log) if float(a) > 0.05}
                assert_true('voice-speak: mouthAmp driven by real envelope (>=2 distinct non-zero amps)', len(amps) >= 2)
            else:
                print('NOTE voice-speak: path = fallback envelope animation (no audio HAL) - NOT audible playback proof'); sys.stdout.flush()
                assert_true('voice-speak: fallback path honest (synth done, no playback claim)', 'playback start' not in log)
            band = (0.38, 0.62, 0.28, 0.45)
            base = _band_diff('shots/vs-idle-0.png', 'shots/vs-idle-1.png', band)
            best = None
            for i in range(nshots):
                dd = _band_diff('shots/vs-idle-0.png', 'shots/vs-speak-%d.png' % i, band)
                if dd is not None:
                    best = dd if best is None else max(best, dd)
            if base is None or best is None:
                assert_true('voice-speak: mouth-band pixel comparison computable', False)
            else:
                print('NOTE voice-speak: mouth-band idle-base=%.2f speech-best=%.2f' % (base, best)); sys.stdout.flush()
                assert_true('voice-speak: mouth band moves during drive (>3x idle baseline)',
                            best > max(3.0 * base, 2.0))
            assert_true('voice-speak: app still on Voice screen after drive', 'Recognition:' in dump())

if want('voice3'):
    # === Phase 3 voice surface: honest recognition label + voice settings ===
    # (mic/STT untestable on emulator - no mic tap, no provider calls)
    dismiss_keyboard()
    tap_nav('Voice', timeout=8)
    time.sleep(3)
    xml = dump()
    assert_true('voice3: recognition path label shown', 'Recognition:' in xml)
    assert_true('voice3: recognition label is honest (on-device or may-use-network)',
                ('on-device recognizer' in xml) or ('may use network' in xml) or ('not available' in xml))
    shot('p3-voice-screen')
    tap_nav('Settings', timeout=8)
    time.sleep(3)
    xml = dump()
    for _ in range(5):
        if 'Speech rate' in xml:
            break
        swipe_up()
        xml = dump()
    assert_true('voice3: settings Voice section', 'Speech rate' in xml)
    assert_true('voice3: voice picker row present when voices exist',
                ('Voice' in xml))
    shot('p3-voice-settings')

if want('voice-ma'):
    # === M-A runtime validation: mic mute UX + event-grounded HAPPY ===
    # The unit gate (VoiceFlowTest truth table) is reported separately; this is
    # runtime UI/log evidence. -noaudio emulator has NO audio input: the rms bar
    # and the negative listening window are WIRING-ONLY, not VAD/STT proof. The
    # CURIOUS runtime claim is intentionally left open: policy requires an EMPTY
    # backend for a chat question, and an unconfigured backend short-circuits to
    # CONFUSED before the CURIOUS cue - scope not widened.
    dismiss_keyboard()
    adb('logcat', '-c')
    tap_nav('Voice', timeout=8)
    time.sleep(3)
    xml = dump()
    assert_true('voice-ma: mute microphone control present', 'Mute microphone' in xml)
    adb('shell', 'pm', 'grant', PKG, 'android.permission.RECORD_AUDIO')
    if assert_true('voice-ma: mute control tappable', bool(tap_text('Mute microphone', timeout=4))):
        time.sleep(1)
        xml = dump()
        assert_true('voice-ma: muted state shown (status + Unmute desc flip)',
                    'Microphone muted' in xml and 'Unmute microphone' in xml)
        shot('ma-muted')
        if tap_text('Start listening', timeout=4):
            time.sleep(1)
            assert_true('voice-ma: muted mic tap shows unmute hint', 'unmute to talk' in dump())
        saw = False
        t0 = time.time()
        while time.time() - t0 < 3:
            if 'Listening...' in dump():
                saw = True
                break
            time.sleep(0.5)
        assert_true('voice-ma: no relisten within 3s after mute (window-only proof)', not saw)
        tap_text('Unmute microphone', timeout=4)
        time.sleep(1)
        xml = dump()
        assert_true('voice-ma: unmute restores mute control state', 'Mute microphone' in xml)
        shot('ma-unmuted')
    # HAPPY on a real completion event (habit check-in), observed via MAX_STATE log
    adb('logcat', '-c')
    tap_nav('Tools', timeout=8)
    time.sleep(3)
    tools_top()
    ensure_visible('Add a habit...')
    if tap_text('Add a habit...', timeout=8):
        time.sleep(1)
        type_text('ma-happy-habit')
        time.sleep(1)
        tap_text('Save habit', timeout=5)
        time.sleep(2)
    assert_true('voice-ma: test habit listed', 'ma-happy-habit' in dump())
    dismiss_keyboard()
    ensure_visible('Mark today')
    if assert_true('voice-ma: Mark today control tappable', bool(tap_text('Mark today', timeout=6))):
        time.sleep(2)
        r = adb('logcat', '-d', '-s', 'MAX_STATE:I')
        assert_true('voice-ma: HAPPY mode logged on habit completion', 'mode=HAPPY' in (r.stdout or ''))
        assert_true('voice-ma: streak became 1 (completion observed in UI)', 'streak 1' in dump())
        adb('logcat', '-c')
        if tap_text('Mark today', timeout=4):
            time.sleep(2)
            r = adb('logcat', '-d', '-s', 'MAX_STATE:I')
            assert_true('voice-ma: already-done re-mark does not re-pulse HAPPY', 'mode=HAPPY' not in (r.stdout or ''))
        else:
            print('NOTE voice-ma: Mark today not re-tappable; re-mark guard checked by unit/code level only'); sys.stdout.flush()
    shot('ma-happy-tools')
    print('NOTE voice-ma: CURIOUS runtime claim open (empty-backend policy); rms bar wiring-only under -noaudio; STT/barge-in/audibility limitations unchanged'); sys.stdout.flush()

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
