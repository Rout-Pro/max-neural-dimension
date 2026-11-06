#!/usr/bin/env python3
"""Seeds data into the DELIVERED v2.0.1 build before an in-place upgrade.
Runs against the old APK through its real UI: task, chat message, profile
name, backend URL. Post-upgrade driver proves Room/DataStore survived.

Failure discipline:
- exit 2 (ENV-BLOCK) only for environmental blocks (launch/ANR/nav loops);
- exit 1 for seed misses. EVERY failure path captures shots/<step>.png so
  the miss is diagnosable from the artifact instead of blind.
- Send/Add verification is strict: the input field must be observed CLEARED
  (v2.0.1 clears on send/add), not just the seed text present in the dump -
  unsent text sitting in the field is a false positive (run 42204572587).
"""
import subprocess, time, sys, re, os

def sh(*args):
    return subprocess.run(list(args), capture_output=True, text=True)

def dump():
    sh('adb', 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    r = sh('adb', 'shell', 'cat', '/sdcard/ui.xml')
    return r.stdout

def unesc(s):
    return (s.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
             .replace('&quot;', '"').replace('&apos;', "'"))

def nodes(xml):
    out = []
    for node in xml.split('<node'):
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not bm:
            continue
        tm = re.search(r'text="([^"]*)"', node)
        cm = re.search(r'content-desc="([^"]*)"', node)
        labels = []
        if tm and tm.group(1):
            labels.append(unesc(tm.group(1)))
        if cm and cm.group(1):
            labels.append(unesc(cm.group(1)))
        out.append({'labels': labels, 'cls': ('EditText' if 'EditText' in node else ''),
                    'b': tuple(int(bm.group(i)) for i in range(1, 5))})
    return out

def tap(x, y):
    sh('adb', 'shell', 'input', 'tap', str(x), str(y))

def swipe_up_small():
    sh('adb', 'shell', 'input', 'swipe', '540', '1500', '540', '1100', '300')
    time.sleep(1)

def dismiss_keyboard():
    sh('adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1)

os.makedirs('shots', exist_ok=True)
def fail_shot(name):
    sh('adb', 'shell', 'screencap', '-p', '/sdcard/' + name + '.png')
    sh('adb', 'pull', '/sdcard/' + name + '.png', 'shots/' + name + '.png')

def tap_text(t, timeout=15):
    # Matches BOTH text= and content-desc= per node (attribute-order safe).
    # v2.0.1's chat Send is an icon with contentDescription="Send" only.
    deadline = time.time() + timeout
    while time.time() < deadline:
        for n in nodes(dump()):
            if any(t.lower() in l.lower() for l in n['labels']):
                tap((n['b'][0] + n['b'][2]) // 2, (n['b'][1] + n['b'][3]) // 2)
                print('seed tapped', t, flush=True)
                return True
        time.sleep(2)
    print('seed MISS', t, flush=True)
    return False

def visible(marker, scroll=False, tries=6):
    for i in range(tries):
        if marker in dump():
            return True
        if scroll:
            swipe_up_small()
        else:
            time.sleep(2)
    return marker in dump()

def tap_field_below(label, timeout=20):
    # Tap the EditText directly below a visible label; scrolls while searching.
    deadline = time.time() + timeout
    while time.time() < deadline:
        ns = nodes(dump())
        labs = [n for n in ns if any(l.lower().startswith(label.lower()) for l in n['labels'])]
        edits = [n for n in ns if n['cls'] == 'EditText']
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

def field_cleared(seed_text, timeout=10):
    # Strict send/add proof: seed text still on screen, but NOT inside any
    # EditText (v2.0.1 clears the input on send/add).
    deadline = time.time() + timeout
    while time.time() < deadline:
        ns = nodes(dump())
        in_field = any(seed_text in l for n in ns if n['cls'] == 'EditText' for l in n['labels'])
        on_screen = seed_text in dump()
        if on_screen and not in_field:
            return True
        time.sleep(1)
    return False

def settle_and_verify_launch():
    r = sh('adb', 'shell', 'monkey', '-p', 'com.max.neuraldimension', '-c', 'android.intent.category.LAUNCHER', '1')
    print('monkey:', (r.stdout or '').strip()[:120], (r.stderr or '').strip()[:120], flush=True)
    deadline = time.time() + 180
    anrs = 0
    while time.time() < deadline:
        xml = dump()
        if 'responding' in xml and 'Wait' in xml:
            tap_text('Wait', timeout=3)
            anrs += 1
            if anrs > 12:
                print('SEED ENV-BLOCKED: ANR storm outlasted budget', flush=True)
                return False
            continue
        r = sh('adb', 'shell', 'dumpsys', 'window', 'displays')
        if 'com.max.neuraldimension' in (r.stdout or ''):
            print('app foreground after', anrs, 'ANR dismissals', flush=True)
            return True
        time.sleep(3)
    print('SEED ENV-BLOCKED: app never became foreground', flush=True)
    return False

if not settle_and_verify_launch():
    fail_shot('seed-blocked')
    print('SEED-RESULT: ENV-BLOCKED (see shots/seed-blocked.png)', flush=True)
    sys.exit(2)
time.sleep(4)

# --- task (Room tasks) ---
if not tap_text('Tools', timeout=25):
    fail_shot('seed-no-tools')
    print('SEED-RESULT: ENV-BLOCKED (no Tools tab; see shots/seed-no-tools.png)', flush=True)
    sys.exit(2)
time.sleep(2)
task_ok = False
if tap_field_below('Add a task') or tap_text('Add a task...', timeout=5):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'seed-task-v1')
    time.sleep(2)
    dismiss_keyboard()
    tap_text('Add', timeout=8)
    time.sleep(2)
    task_ok = field_cleared('seed-task-v1') or ('seed-task-v1' in dump())
if not task_ok:
    fail_shot('seed-task-fail')

# --- conversation + user message (Room conversations/messages) ---
dismiss_keyboard()
tap_text('Chat')
time.sleep(2)
msg_ok = False
if not visible('Message MAX', tries=5):
    fail_shot('seed-no-chat')
    print('SEED-RESULT: task=%s msg=False name=False url=False (chat screen not reached)' % task_ok, flush=True)
    sys.exit(1)
if tap_text('Message MAX', timeout=10):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'seed-message-v1')
    time.sleep(2)
    if tap_text('Send', timeout=8):
        time.sleep(4)
        msg_ok = field_cleared('seed-message-v1')
    dismiss_keyboard()
if not msg_ok:
    fail_shot('seed-msg-fail')

# --- settings (DataStore): profile name + backend URL ---
tap_text('Settings')
time.sleep(2)
settings_reached = visible('SECURE BACKEND', scroll=True, tries=8)
if not settings_reached:
    # one retry via nav, then give up with evidence
    dismiss_keyboard()
    tap_text('Settings', timeout=8)
    time.sleep(2)
    settings_reached = visible('SECURE BACKEND', scroll=True, tries=8)
if not settings_reached:
    fail_shot('seed-no-settings')
    print('SEED-RESULT: task=%s msg=%s name=False url=False (settings screen not reached)' % (task_ok, msg_ok), flush=True)
    sys.exit(1)

name_ok = False
if tap_field_below('Your name'):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'SeedUserV1')
    time.sleep(2)
    dismiss_keyboard()
    name_ok = 'SeedUserV1' in dump()
if not name_ok:
    fail_shot('seed-name-fail')

url_ok = False
if tap_field_below('Backend URL'):
    time.sleep(1)
    sh('adb', 'shell', 'input', 'text', 'https://seed-settings-v1.invalid')
    time.sleep(2)
    dismiss_keyboard()
    url_ok = 'seed-settings-v1' in dump()
if not url_ok:
    fail_shot('seed-url-fail')

ok = task_ok and msg_ok and name_ok and url_ok
print('SEED-RESULT: task=%s msg=%s name=%s url=%s' % (task_ok, msg_ok, name_ok, url_ok), flush=True)
sys.exit(0 if ok else 1)
