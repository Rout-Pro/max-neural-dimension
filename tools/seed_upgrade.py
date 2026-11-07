#!/usr/bin/env python3
"""Seeds data into the DELIVERED v2.0.1 build before an in-place upgrade.
Runs against the old APK through its real UI: task, chat message, profile
name, backend URL. Post-upgrade driver proves Room/DataStore survived.

Helper conventions are copied from tools/drive_ui.py (the proven driver):
char-by-char typing (full-string `input text` drops chars on SwiftShader),
IME-gated BACK (ungated BACK finishes this single-activity app - pixel-proven
run 42212127418), ANR riding, exact-match nav taps.

Failure discipline:
- exit 2 (ENV-BLOCK) only for environmental blocks; exit 1 for seed misses.
- EVERY failure path captures shots/<step>.png.
- Add/Send verification is strict: the field's placeholder must be restored
  (v2.0.1 clears the input only inside the button onClick) and the seed text
  must appear outside any EditText. The chat message must additionally survive
  force-stop + picker re-open (conversation history, not live VM state).
"""
import subprocess, time, sys, re, os

REAL_URL = os.environ.get('ND_REAL_URL', '').strip()
APP_TOKEN = os.environ.get('ND_APP_TOKEN', '').strip()
FAKE = bool(os.environ.get('ND_FAKE_STATE'))
SEED_URL = REAL_URL or 'https://seed-settings-v1.invalid'
URL_MARK = 'workers.dev' if REAL_URL else 'seed-settings-v1'

PKG = 'com.max.neuraldimension'

def sh(*args):
    return subprocess.run(list(args), capture_output=True, text=True)

def dump():
    sh('adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    r = sh('adb', 'shell', 'cat', '/sdcard/ui.xml')
    return r.stdout

def unesc(s):
    return (s.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
             .replace('&quot;', '"').replace('&apos;', "'"))

def parse_nodes(xml):
    out = []
    for raw in xml.split('<node'):
        tm = re.search(r'text="([^"]*)"', raw)
        cm = re.search(r'content-desc="([^"]*)"', raw)
        cl = re.search(r'class="([^"]*)"', raw)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', raw)
        if not bm:
            continue
        labels = []
        if tm and tm.group(1):
            labels.append(unesc(tm.group(1)))
        if cm and cm.group(1):
            labels.append(unesc(cm.group(1)))
        out.append({'labels': labels, 'cls': cl.group(1) if cl else '',
                    'b': tuple(int(bm.group(i)) for i in range(1, 5))})
    return out

def tap(x, y):
    sh('adb', 'shell', 'input', 'tap', str(x), str(y))

def tap_text(t, timeout=15, exact=False):
    # Matches BOTH text= and content-desc= per node (attribute-order safe).
    # v2.0.1's chat Send is an icon with contentDescription="Send" only.
    deadline = time.time() + timeout
    while time.time() < deadline:
        for n in parse_nodes(dump()):
            for l in n['labels']:
                if (exact and l == t) or (not exact and t.lower() in l.lower()):
                    tap((n['b'][0] + n['b'][2]) // 2, (n['b'][1] + n['b'][3]) // 2)
                    print('seed tapped', t, flush=True)
                    return True
        time.sleep(2)
    print('seed MISS', t, flush=True)
    return False

def type_text(s):
    # Char-by-char (drive_ui convention): full-string `input text` drops
    # characters on SwiftShader emulators and mishandles shell-specials.
    for ch in s:
        if ch == ' ':
            sh('adb', 'shell', 'input', 'text', '%s')
        elif ch == "'":
            sh('adb', 'shell', 'input', 'text', "\"'\"")  # apostrophe, device-shell-safe
        else:
            sh('adb', 'shell', 'input', 'text', "'" + ch + "'")
        time.sleep(0.12)

def keyboard_shown():
    r = sh('adb', 'shell', 'dumpsys', 'input_method')
    out = r.stdout or ''
    return 'mInputShown=true' in out or 'mImeWindowVis=1' in out

def dismiss_keyboard():
    # BACK collapses the IME without navigating when a keyboard is showing.
    # Without a keyboard, BACK finishes this single-activity app (proven).
    if not keyboard_shown():
        print('seed dismiss_keyboard: no IME, skipping BACK', flush=True)
        return
    sh('adb', 'shell', 'input', 'keyevent', '4')
    time.sleep(1)

def dismiss_anr():
    xml = dump()
    if 'responding' in xml and 'Wait' in xml:
        tap_text('Wait', timeout=3, exact=True)
        time.sleep(2)
        return True
    return False

def swipe_up_small():
    sh('adb', 'shell', 'input', 'swipe', '540', '1500', '540', '1100', '300')
    time.sleep(1)

os.makedirs('shots', exist_ok=True)
def fail_shot(name):
    sh('adb', 'shell', 'screencap', '-p', '/sdcard/' + name + '.png')
    sh('adb', 'pull', '/sdcard/' + name + '.png', 'shots/' + name + '.png')

def app_foreground():
    r = sh('adb', 'shell', 'dumpsys', 'window', 'displays')
    return PKG in (r.stdout or '')

def settle_and_verify_launch():
    r = sh('adb', 'shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    print('monkey:', (r.stdout or '').strip()[:120], (r.stderr or '').strip()[:120], flush=True)
    deadline = time.time() + 180
    anrs = 0
    while time.time() < deadline:
        if dismiss_anr():
            anrs += 1
            if anrs > 12:
                print('SEED ENV-BLOCKED: ANR storm outlasted budget', flush=True)
                return False
            continue
        if app_foreground():
            print('app foreground after', anrs, 'ANR dismissals', flush=True)
            return True
        time.sleep(3)
    print('SEED ENV-BLOCKED: app never became foreground', flush=True)
    return False

def ensure_app(step):
    if app_foreground():
        return True
    print('app not foreground before', step, '- relaunching', flush=True)
    if settle_and_verify_launch():
        time.sleep(3)
        return True
    fail_shot('seed-relaunch-' + step)
    print('SEED-RESULT: ENV-BLOCKED (relaunch before %s)' % step, flush=True)
    sys.exit(2)

def tap_nav(label, timeout=12):
    # drive_ui convention: exact match; survive open keyboard and ANR dialogs.
    if tap_text(label, timeout=3, exact=True):
        return True
    dismiss_keyboard()
    dismiss_anr()
    return tap_text(label, timeout=timeout, exact=True)

def visible(marker, scroll=False, tries=6):
    for i in range(tries):
        if marker in dump():
            return True
        if scroll:
            swipe_up_small()
        else:
            time.sleep(2)
    return marker in dump()

def field_text_below(label):
    # Reader mirroring tap_field_below geometry (label -> nearest EditText below).
    ns = parse_nodes(dump())
    labs = [n for n in ns if any(l.lower().startswith(label.lower()) for l in n['labels'])]
    edits = [n for n in ns if 'EditText' in n['cls']]
    for l in labs:
        cands = [e for e in edits if l['b'][1] - 10 <= e['b'][1] <= l['b'][3] + 260]
        if cands:
            e = min(cands, key=lambda e: e['b'][1])
            return e['labels'][0] if e['labels'] else ''
    return None

def clear_field():
    # Fields may be pre-filled (v2.0.1 default userName='Rout' - run 42267432732
    # pixel: CTRL+A+DEL alone did NOT clear the Compose BasicTextField, seed
    # produced 'RoutSeedUserV1' twice). Full driver-proven sequence.
    sh('adb', 'shell', 'input', 'keyevent', '--keycomb', '113', '29')  # CTRL+A
    time.sleep(0.3)
    sh('adb', 'shell', 'input', 'keyevent', '67')                      # DEL
    sh('adb', 'shell', 'input', 'keyevent', '123')                     # MOVE_HOME
    for _ in range(60):
        sh('adb', 'shell', 'input', 'keyevent', '112')                 # FORWARD_DEL
    for _ in range(60):
        sh('adb', 'shell', 'input', 'keyevent', '67')                  # DEL

def clear_field_verified(label):
    # Never type into an unverified field: clear, then READ BACK the actual
    # field below the label. An unverifiable clear is an honest seed failure,
    # not a merged-value pass (main's rule after run 42267432732).
    for attempt in range(3):
        clear_field()
        time.sleep(0.5)
        v = field_text_below(label)
        if v is not None and v == '':
            print('seed field verified empty:', label, flush=True)
            return True
        print('seed clear attempt %d for %s not empty' % (attempt + 1, label), flush=True)
    return False

def tap_field_below(label, timeout=15):
    # Settings Field composable: Text(label) above a BasicTextField. Search
    # label first WITHOUT scrolling (screen settled), small swipes only while
    # the label itself is absent, then tap the nearest EditText below it.
    deadline = time.time() + timeout
    while time.time() < deadline:
        ns = parse_nodes(dump())
        labs = [n for n in ns if any(l.lower().startswith(label.lower()) for l in n['labels'])]
        if labs:
            edits = [n for n in ns if 'EditText' in n['cls']]
            for l in labs:
                cands = [e for e in edits if l['b'][1] - 10 <= e['b'][1] <= l['b'][3] + 260]
                if cands:
                    e = min(cands, key=lambda e: e['b'][1])
                    tap((e['b'][0] + e['b'][2]) // 2, (e['b'][1] + e['b'][3]) // 2)
                    print('seed tapped field below', label, flush=True)
                    return True
        swipe_up_small()
    print('seed MISS field below', label, flush=True)
    return False

def committed(seed_text, placeholder, timeout=12):
    # Strict add/send proof: placeholder restored (v2.0.1 clears the field
    # only inside the button onClick) AND seed text outside any EditText.
    deadline = time.time() + timeout
    while time.time() < deadline:
        ns = parse_nodes(dump())
        in_field = any(seed_text in l for n in ns if 'EditText' in n['cls'] for l in n['labels'])
        on_screen = any(seed_text in l for n in ns if 'EditText' not in n['cls'] for l in n['labels'])
        if placeholder in dump() and on_screen and not in_field:
            return True
        time.sleep(1)
    return False

if not settle_and_verify_launch():
    fail_shot('seed-blocked')
    print('SEED-RESULT: ENV-BLOCKED (see shots/seed-blocked.png)', flush=True)
    sys.exit(2)
time.sleep(4)

# --- task (Room tasks) ---
if not tap_nav('Tools', timeout=25):
    fail_shot('seed-no-tools')
    print('SEED-RESULT: ENV-BLOCKED (no Tools tab; see shots/seed-no-tools.png)', flush=True)
    sys.exit(2)
time.sleep(2)
task_ok = False
if tap_text('Add a task...', timeout=15):
    time.sleep(1)
    type_text('seed-task-v1')
    time.sleep(1)
    dismiss_keyboard()
    tap_text('Add', timeout=8, exact=True)
    time.sleep(2)
    task_ok = committed('seed-task-v1', 'Add a task...')
if not task_ok:
    fail_shot('seed-task-fail')

# --- conversation + user message (Room conversations/messages) ---
ensure_app('chat')
tap_nav('Chat')
time.sleep(2)
msg_ok = False
if not visible('Message MAX', tries=5):
    fail_shot('seed-no-chat')
    print('SEED-RESULT: task=%s msg=False name=False url=False (chat screen not reached)' % task_ok, flush=True)
    sys.exit(1)
if tap_text('Message MAX', timeout=10):
    time.sleep(1)
    type_text('seed-message-v1')
    time.sleep(1)
    if tap_text('Send', timeout=8, exact=True):
        msg_ok = committed('seed-message-v1', 'Message MAX')
    dismiss_keyboard()
if not msg_ok:
    fail_shot('seed-msg-fail')
else:
    # Conversation-history proof: restart, re-open via picker. A live bubble
    # only proves VM state; force-stop + picker selection proves the Room row.
    sh('adb', 'shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    if not settle_and_verify_launch():
        fail_shot('seed-relaunch-blocked')
        print('SEED-RESULT: ENV-BLOCKED (relaunch after force-stop)', flush=True)
        sys.exit(2)
    time.sleep(3)
    tap_nav('Chat')
    time.sleep(2)
    hist_ok = False
    if tap_text('New conversation', timeout=10, exact=True):  # header picker button
        time.sleep(2)
        if tap_text('seed-message-v1', timeout=10, exact=True):  # dropdown item
            time.sleep(3)
            hist_ok = 'seed-message-v1' in dump()
    if not hist_ok:
        fail_shot('seed-history-fail')
        print('seed history check FAILED: message did not survive restart/picker', flush=True)
    msg_ok = hist_ok

# --- settings (DataStore): profile name + backend URL ---
ensure_app('settings')
tap_nav('Settings')
time.sleep(2)
if not visible('SECURE BACKEND', scroll=True, tries=8):
    fail_shot('seed-no-settings')
    print('SEED-RESULT: task=%s msg=%s name=False url=False (settings screen not reached)' % (task_ok, msg_ok), flush=True)
    sys.exit(1)

name_ok = False
if tap_field_below('Your name'):
    time.sleep(1)
    if not clear_field_verified('Your name'):
        fail_shot('seed-name-notcleared')
        print('SEED-RESULT: task=%s msg=%s name=False url=False (name field not verifiably empty)' % (task_ok, msg_ok), flush=True)
        sys.exit(1)
    type_text('SeedUserV1')
    time.sleep(1)
    dismiss_keyboard()
    name_ok = 'SeedUserV1' in dump()
if not name_ok:
    fail_shot('seed-name-fail')

url_ok = False
if tap_field_below('Backend URL'):
    time.sleep(1)
    if not clear_field_verified('Backend URL'):
        fail_shot('seed-url-notcleared')
        print('SEED-RESULT: task=%s msg=%s name=%s url=False (url field not verifiably empty)' % (task_ok, msg_ok, name_ok), flush=True)
        sys.exit(1)
    type_text(SEED_URL)
    time.sleep(1)
    dismiss_keyboard()
    url_ok = URL_MARK in dump()
if not url_ok:
    fail_shot('seed-url-fail')

token_ok = None
if REAL_URL:
    # chat-real mode: seed the real app token through the MASKED Settings field.
    # Guard: type a dummy first and require it to be INVISIBLE in dumps (masked
    # field); fail fast before the real token is ever typed if it is not.
    token_ok = False
    if tap_field_below('App token'):
        time.sleep(1)
        if not clear_field_verified('App token'):
            fail_shot('seed-token-notcleared')
            print('SEED-RESULT: task=%s msg=%s name=%s url=%s token=False (token field not verifiably empty)' % (task_ok, msg_ok, name_ok, url_ok), flush=True)
            sys.exit(1)
        type_text('ND-MASK-CHECK-DUMMY')
        time.sleep(1)
        dismiss_keyboard()
        if FAKE:
            print('seed token mask check skipped (fake adb)', flush=True)
        else:
            if 'ND-MASK-CHECK-DUMMY' in dump():
                fail_shot('seed-token-mask-fail')
                print('SEED-RESULT: TOKEN-MASK-FAIL (dummy token visible in dump; real token never typed)', flush=True)
                sys.exit(1)
            print('seed token field masked in dump: true', flush=True)
        if not tap_field_below('App token') or not clear_field_verified('App token'):
            fail_shot('seed-token-notcleared2')
            sys.exit(1)
        if not APP_TOKEN:
            print('SEED-RESULT: TOKEN-MISSING (ND_APP_TOKEN env empty)', flush=True)
            sys.exit(1)
        type_text(APP_TOKEN)  # value is never printed/logged
        time.sleep(1)
        dismiss_keyboard()
        token_ok = True
        print('seed app token set (masked field; value never printed)', flush=True)

time.sleep(3)  # let async DataStore writes land before install -r
ok = task_ok and msg_ok and name_ok and url_ok
print('SEED-RESULT: task=%s msg=%s name=%s url=%s%s' % (task_ok, msg_ok, name_ok, url_ok, ' token=%s' % token_ok if token_ok is not None else ''), flush=True)
sys.exit(0 if ok else 1)
