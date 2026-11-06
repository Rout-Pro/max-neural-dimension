#!/usr/bin/env python3
"""Fake adb: a deterministic Android device model for LOCAL harness testing of
tools/run_ui_pass.sh + tools/seed_upgrade.py + tools/drive_ui.py (release flow).
Models: install/install -r (kills app), launcher vs app foreground, uiautomator
dumps with package-stamped nodes, IME, BACK semantics (dropdown>IME>exit),
seeded v2.0.1 -> upgraded v2.1 data preservation, notifications, export files,
AND SCROLL GEOMETRY: Tools/Settings content is taller than the viewport,
sections inherit scroll position (tap_nav does NOT reset it), ensure_visible's
down-only swipes cannot reach targets above the viewport (run 42248503219).
State in $ND_FAKE_STATE/state.json. Env mutants: ND_FAKE_NO_CREATE=1 (studio
create no-op), ND_FAKE_LAUNCH_FAIL=1 / ND_FAKE_LAUNCH_FAIL_AFTER=k.
NEVER used against a real device; exists to prove the harness itself.
"""
import json, os, sys, base64, shutil

ST = os.environ.get('ND_FAKE_STATE', '/tmp/nd-fake-state')
PKG = 'com.max.neuraldimension'
LAUNCHER_PKG = 'com.google.android.apps.nexuslauncher'
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==')
VIEW_TOP, VIEW_BOTTOM = 140, 2050   # content viewport; nav bar sits at 2100

def sp(*p):
    return os.path.join(ST, 'fs', *p)

def load():
    try:
        return json.load(open(os.path.join(ST, 'state.json')))
    except Exception:
        return default_state()

def default_state():
    return {
        'version': None, 'running': False, 'foreground': 'launcher',
        'screen': 'home', 'ime': False, 'focused': None,
        'dropdown': False, 'panel': False, 'template_dd': False, 'notif_dialog': False,
        'tasks': [], 'conversations': {}, 'current_conv': None,
        'settings': {'userName': 'Rout', 'assistantName': 'MAX', 'backendUrl': '', 'appToken': ''},
        'habits': [], 'expenses': [], 'notes': [], 'reminders': [],
        'projects': [], 'editor': None, 'console_open': False, 'ran': False,
        'weather': False, 'briefing': False, 'notifications': [],
        'export_notice': False, 'fields': {}, 'scroll': {}, 'launch_count': 0,
    }

def save(s):
    os.makedirs(ST, exist_ok=True)
    json.dump(s, open(os.path.join(ST, 'state.json'), 'w'))

def esc(t):
    return (t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
             .replace('"', '&quot;').replace("'", '&apos;'))

class Node:
    def __init__(self, text='', desc='', cls='android.widget.TextView', pkg=PKG, y=0, x=40, w=1000, h=70):
        self.text, self.desc, self.cls, self.pkg = text, desc, cls, pkg
        self.x, self.y, self.w, self.h = x, y, w, h
    def xml(self):
        return ('<node index="0" text="%s" resource-id="" class="%s" package="%s" content-desc="%s" '
                'checkable="false" checked="false" clickable="true" enabled="true" focusable="true" '
                'focused="false" scrollable="false" long-clickable="false" password="false" selected="false" '
                'bounds="[%d,%d][%d,%d]" />' % (esc(self.text), self.cls, self.pkg, esc(self.desc),
                                                self.x, self.y, self.x + self.w, self.y + self.h))
    def hit(self, px, py):
        return self.x <= px <= self.x + self.w and self.y <= py <= self.y + self.h

NAV_Y = 2100
def nav_nodes(s):
    labels = ['Home', 'Chat', 'Voice', 'Tools', 'Settings']
    if s['version'] == '2.1':
        labels.insert(4, 'Studio')
    xs = {'Home': 60, 'Chat': 240, 'Voice': 420, 'Tools': 600, 'Studio': 780, 'Settings': 940}
    return [Node(text=l, y=NAV_Y, x=xs[l], w=140, h=80) for l in labels]

def field_node(s, key, placeholder, y, x=40, w=1000):
    val = s['fields'].get(key, '')
    return Node(text=val if val else placeholder, cls='android.widget.EditText', y=y, x=x, w=w)

def stack(s, scr):
    """Full content document for a screen: (nodes at document positions, doc bottom)."""
    n = []
    y = VIEW_TOP
    def add(text='', desc='', cls='android.widget.TextView', pkg=PKG, dy=85, **kw):
        nonlocal y
        nd = Node(text=text, desc=desc, cls=cls, pkg=pkg, y=y, **kw)
        n.append(nd)
        y += dy
        return nd
    if scr == 'home':
        add('NEURAL DIMENSION', dy=120)
        add('AI STATUS', dy=85)
        add('TASKS', dy=85)
        for t in s['tasks']:
            add(t, dy=60)
        if s['panel']:
            add('Provider', dy=70); add('Secure backend status', dy=70); add('Close', dy=70)
    elif scr == 'chat':
        if s['dropdown']:
            add('New conversation', dy=70)
            for title in s['conversations']:
                add(title, dy=70)
        else:
            title = s['current_conv'] or 'New conversation'
            add(title, desc='', dy=70)
            n.append(Node(desc='Conversations', y=150, x=950, w=90, h=60))
            y = 260
            for m in s['conversations'].get(s['current_conv'], []):
                if m['role'] == 'user':
                    add(m['text'], dy=70)
                else:
                    add(m['text'], dy=60)
                    if m['text'] == 'Secure backend is not connected yet':
                        add('MAX answers through your secure backend, which holds the AI provider keys server-side.', dy=60)
                        add('Open Settings', dy=60)
                    elif m['text'] == 'Request failed':
                        add('The backend could not answer this request. Please try again.', dy=60)
            y = 1950
            n.append(field_node(s, 'chat', 'Message MAX', y)); y += 90
            n.append(Node(desc='Send', y=y - 90, x=1000, w=70, h=70))
    elif scr == 'voice':
        add('LIVE WITH', dy=100)
        add('Recognition: on-device recognizer', dy=85)
    elif scr == 'tools':
        # Real card order (ToolsScreen.kt): briefing, Tasks, Notes, Reminders,
        # Habits, Expenses, Weather, Flashlight, Device, Open apps, Controls.
        add('TOOLS', dy=120)
        add('Daily briefing', dy=110)
        add('Refresh briefing', dy=85)
        if s['briefing']:
            add('%d open task%s' % (len(s['tasks']), 's' if len(s['tasks']) != 1 else ''), dy=70)
        add('Tasks', dy=110)
        n.append(field_node(s, 'task', 'Add a task...', y)); y += 85
        add('Add', x=900, w=140, dy=70)
        for t in s['tasks']:
            add(t, dy=60)
        add('Notes', dy=110)
        n.append(field_node(s, 'note', 'Add a note...', y)); y += 85
        add('Save note', dy=70)
        for t in s['notes']:
            add(t, dy=60)
        add('Reminders', dy=110)
        n.append(field_node(s, 'remtitle', 'Reminder title...', y)); y += 85
        n.append(field_node(s, 'remmin', 'min', y)); y += 85
        add('Schedule', dy=70)
        for r in s['reminders']:
            add('%s in %s min' % (r['title'], r['min']), dy=60)
        if s['reminders']:
            add('Cancel', dy=60)
        add('Habits', dy=110)
        n.append(field_node(s, 'habit', 'Add a habit...', y)); y += 85
        add('Save habit', dy=70)
        for h in s['habits']:
            add('%s - streak %d' % (h['name'], h['streak']), dy=60)
        if s['habits']:
            add('Mark today', dy=60)
        add('Expenses', dy=110)
        n.append(field_node(s, 'expense', 'Expense label...', y)); y += 85
        n.append(field_node(s, 'amount', 'Rs.', y)); y += 85
        add('Save expense', dy=70)
        for e in s['expenses']:
            add('%s Rs. %.2f' % (e['label'], e['cents'] / 100.0), dy=60)
        add('Total: Rs. %.2f' % (sum(e['cents'] for e in s['expenses']) / 100.0), dy=70)
        add('Weather', dy=110)
        n.append(field_node(s, 'city', 'City name...', y)); y += 85
        add('Check weather', dy=70)
        if s['weather']:
            add('Bhubaneswar, India: 24.6C, clear sky, humidity 96%, wind 1.2 km/h', dy=70)
        add('Flashlight', dy=110)
        add('Flashlight off', dy=70)
        add('Device', dy=110)
        add('Battery: 87%', dy=60); add('Storage free: 4.7 GB of 5.8 GB', dy=60)
        add('RAM free: 1.2 GB of 2.4 GB', dy=60); add('Model: sdk_gphone64_x86_64 - Android 14 (API 34)', dy=60)
        add('Uptime: 0h 4m', dy=60)
        add('Open apps', dy=110)
        add('Calendar Camera Chrome Clock', dy=60); add('Files Gmail Maps', dy=60)
        add('Neural Dimension Phone Settings', dy=60); add('YouTube Drive Photos', dy=60)
        add('Play Store Messages', dy=60); add('Recorder Calculator', dy=60)
        add('Contacts Keep Tasks', dy=60); add('Docs Sheets Slides', dy=60)
        add('Controls', dy=110)
        add('Volume panel', dy=60); add('Media volume', dy=60)
        add('Notifications', dy=60); add('Camera shortcut', dy=60)
        add('Wi-Fi Bluetooth', dy=60); add('Brightness', dy=60)
        add('Rotation lock', dy=60); add('Battery saver', dy=60)
        add('Coming in later phases', dy=110)
        add('Calendar integration, image and video generation.', dy=70)
        add('Each arrives only when it genuinely works.', dy=70)
    elif scr == 'settings':
        add('SETTINGS', dy=110)
        add('Profile', dy=70)
        add('Your name', dy=55)
        n.append(Node(text=s['settings']['userName'], cls='android.widget.EditText', y=y)); y += 80
        add('Assistant name', dy=55)
        n.append(Node(text=s['settings']['assistantName'], cls='android.widget.EditText', y=y)); y += 80
        add('SECURE BACKEND', dy=80)
        add('Backend URL (HTTPS only)', dy=55)
        n.append(Node(text=s['settings']['backendUrl'], cls='android.widget.EditText', y=y)); y += 80
        add('Test connection', dy=70)
        add('Speech rate', dy=60)
        add('Voice', dy=60)
    elif scr == 'studio':
        if s['editor'] is not None:
            code = ("<html><body><h1>Hello from MAX Studio</h1>"
                    "<script>console.log('studio-ready')</script></body></html>")
            n.append(Node(text=code, cls='android.widget.EditText', y=y, h=400)); y += 420
            add('Run', x=60, w=120, dy=0); add('Console (1)' if s['console_open'] else 'Console (0)', x=220, w=220, dy=70)
            if s['console_open']:
                add('studio-ready', dy=60)
            add('Export project as single HTML file', dy=70)
            add('Back to projects', dy=70)
        else:
            if not s['projects']:
                add('No projects yet', dy=90)
            add('Projects run in a sandboxed preview; React templates load React from a CDN.', dy=80)
            n.append(field_node(s, 'projname', 'New project name', y)); y += 85
            add('React (CDN)' if s.get('template') == 'react' else 'Blank page', dy=70)
            if s['template_dd']:
                add('Blank page', dy=60); add('React (CDN)', dy=60)
            add('Create project', dy=70)
            for p in s['projects']:
                add(p['name'], dy=60)
                add('Delete project', dy=60)
        if s['export_notice']:
            add('Exported to Downloads', dy=60)
    return n, y

SCROLLABLE = ('tools', 'settings')

def screen_nodes(s):
    if s['foreground'] == 'launcher':
        return [Node(text='Neural Dimension', pkg=LAUNCHER_PKG, y=900, x=200, w=300),
                Node(text='Gmail', pkg=LAUNCHER_PKG, y=900, x=560, w=200),
                Node(text='Maps', pkg=LAUNCHER_PKG, y=1100, x=200, w=200)]
    scr = s['screen']
    n, _bottom = stack(s, scr)
    if scr in SCROLLABLE:
        off = s.get('scroll', {}).get(scr, 0)
        vis = []
        for nd in n:
            nd.y -= off
            if nd.y + nd.h > VIEW_TOP and nd.y < VIEW_BOTTOM:
                vis.append(nd)
        n = vis
    if s['notif_dialog']:
        n.append(Node(text='Allow MAX to send notifications?', y=800, x=300, w=480))
        n.append(Node(text='Allow', y=880, x=560, w=180, h=60))
        n.append(Node(text='Deny', y=880, x=300, w=180, h=60))
    return n + nav_nodes(s)

def dump_xml(s):
    body = ''.join(nd.xml() for nd in screen_nodes(s))
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><hierarchy rotation="0">' + body + '</hierarchy>'

def matched(nodes, px, py):
    hits = [nd for nd in nodes if nd.hit(px, py)]
    if not hits:
        return None
    return min(hits, key=lambda nd: nd.w * nd.h)

def unquote(a):
    if a == '%s':
        return ' '
    if a.replace('"', '').replace("'", '') == '':
        return "'"
    if len(a) >= 2 and a[0] == "'" and a[-1] == "'":
        return a[1:-1]
    if len(a) >= 2 and a[0] == '"' and a[-1] == '"':
        return a[1:-1]
    return a

def do_tap(s, px, py):
    if s['foreground'] == 'launcher':
        return
    nd = matched(screen_nodes(s), px, py)
    if nd is None:
        return
    t, d = nd.text, nd.desc
    s['export_notice'] = False
    scr = s['screen']
    if s['notif_dialog']:
        if t == 'Allow':
            s['notif_dialog'] = False
            title = s['fields'].get('remtitle', '')
            mins = s['fields'].get('remmin', '') or '1'
            if title:
                s['reminders'].append({'title': title, 'min': mins})
                s['notifications'].append('NotificationRecord pkg=%s tickertext=%s "MAX Reminder: %s"' % (PKG, title, title))
            s['fields']['remtitle'] = ''; s['fields']['remmin'] = ''
        elif t == 'Deny':
            s['notif_dialog'] = False
        return
    if s['dropdown']:
        if t == 'New conversation':
            s['current_conv'] = None
        elif t in s['conversations']:
            s['current_conv'] = t
        s['dropdown'] = False
        return
    if s['template_dd']:
        s['template'] = 'react' if t == 'React (CDN)' else 'blank'
        s['template_dd'] = False
        return
    if t in ('Home', 'Chat', 'Voice', 'Tools', 'Settings', 'Studio') and nd.y >= NAV_Y:
        s['screen'] = t.lower()
        s['editor'] = None
        s['console_open'] = False
        return
    if nd.cls == 'android.widget.EditText':
        key = None
        for k, ph in (('chat', 'Message MAX'), ('task', 'Add a task...'), ('city', 'City name...'),
                      ('habit', 'Add a habit...'), ('expense', 'Expense label...'), ('amount', 'Rs.'),
                      ('note', 'Add a note...'), ('remtitle', 'Reminder title...'), ('remmin', 'min'),
                      ('projname', 'New project name')):
            if t == ph or (s['fields'].get(k) and t == s['fields'][k]):
                key = k
                break
        if key is None and s['screen'] == 'settings':
            # field doc coords: name 375, assistant 510, url 725
            docy = nd.y + s.get('scroll', {}).get('settings', 0)
            key = 'settings:name' if docy < 450 else 'settings:assistant' if docy < 650 else 'settings:url'
        if key is None and s['screen'] == 'studio' and s['editor'] is not None:
            key = 'editor'
        s['focused'] = key
        s['ime'] = True
        # adjustResize: opening the IME pans the content so the focused field
        # sits well above the keyboard (~y900); offset never moves back down.
        if scr in SCROLLABLE:
            sc = s.setdefault('scroll', {})
            _n, bottom = stack(s, scr)
            max_off = max(0, bottom - (VIEW_BOTTOM - VIEW_TOP))
            docy = nd.y + sc.get(scr, 0)
            target = max(0, min(docy - 900, max_off))
            sc[scr] = max(sc.get(scr, 0), target)
        return
    if scr == 'home':
        if t == 'AI STATUS':
            s['panel'] = True
        elif t == 'Close':
            s['panel'] = False
    elif scr == 'chat':
        if d == 'Conversations' or t == (s['current_conv'] or 'New conversation'):
            s['dropdown'] = True
        elif d == 'Send' or t == 'Send':
            msg = s['fields'].get('chat', '')
            if msg:
                s['fields']['chat'] = ''
                if not s['current_conv']:
                    s['current_conv'] = msg[:38] or 'New conversation'
                    s['conversations'][s['current_conv']] = []
                s['conversations'][s['current_conv']].append({'role': 'user', 'text': msg})
                reply = ('Secure backend is not connected yet' if not s['settings']['backendUrl']
                         else 'Request failed')
                s['conversations'][s['current_conv']].append({'role': 'assistant', 'text': reply})
    elif scr == 'tools':
        if t == 'Add':
            v = s['fields'].get('task', '')
            if v:
                s['tasks'].append(v); s['fields']['task'] = ''
        elif t == 'Refresh briefing':
            s['briefing'] = True
        elif t == 'Check weather':
            if s['fields'].get('city', '').strip():
                s['weather'] = True
        elif t == 'Save habit':
            v = s['fields'].get('habit', '')
            if v:
                s['habits'].append({'name': v, 'streak': 0}); s['fields']['habit'] = ''
        elif t == 'Mark today':
            if s['habits']:
                s['habits'][0]['streak'] = 1
        elif t == 'Save expense':
            lab = s['fields'].get('expense', '')
            amt = s['fields'].get('amount', '')
            if lab and amt.isdigit():
                s['expenses'].append({'label': lab, 'cents': int(amt) * 100})
                s['fields']['expense'] = ''; s['fields']['amount'] = ''
        elif t == 'Save note':
            v = s['fields'].get('note', '')
            if v:
                s['notes'].append(v); s['fields']['note'] = ''
        elif t == 'Schedule':
            if s['fields'].get('remtitle', '').strip():
                s['notif_dialog'] = True
        elif t == 'Cancel':
            if s['reminders']:
                gone = s['reminders'].pop(0)
                s['notifications'] = [x for x in s['notifications'] if gone['title'] not in x]
    elif scr == 'studio':
        if t == 'Create project':
            if os.environ.get('ND_FAKE_NO_CREATE') != '1':
                name = s['fields'].get('projname', '') or 'Untitled'
                tpl = s.get('template', 'blank')
                s['projects'].append({'name': name, 'template': tpl})
                s['editor'] = name
                s['fields']['projname'] = ''
                s['ran'] = False; s['console_open'] = False
        elif t in ('Blank page', 'React (CDN)') and s['editor'] is None:
            s['template_dd'] = True
        elif t == 'Run':
            s['ran'] = True
        elif t.startswith('Console ('):
            s['console_open'] = True
        elif t == 'Export project as single HTML file' and s['editor']:
            name = s['editor']
            tpl = next((p['template'] for p in s['projects'] if p['name'] == name), 'blank')
            if tpl == 'react':
                body = ('<html><head><script src="https://unpkg.com/react@18/umd/react.production.min.js"></script>'
                        '<script src="https://unpkg.com/react-dom@18/umd/react-dom.production.min.js"></script>'
                        '<script src="https://unpkg.com/@babel/standalone/babel.min.js"></script></head>'
                        '<body><div id="root"></div><script type="text/babel">'
                        "console.log('studio-ready');</script></body></html>")
            else:
                body = ('<html><head><style>body{font-family:sans-serif}</style></head>'
                        "<body><h1>Hello from MAX Studio</h1><script>console.log('studio-ready')</script></body></html>")
            outdir = sp('sdcard', 'Download', 'NeuralDimension')
            os.makedirs(outdir, exist_ok=True)
            open(os.path.join(outdir, name + '.html'), 'w').write(body)
            s['export_notice'] = True
        elif t == 'Back to projects':
            s['editor'] = None
            s['console_open'] = False
        elif t == 'Delete project' and s['projects']:
            s['projects'].pop(0)

def do_back(s):
    if s['notif_dialog']:
        s['notif_dialog'] = False
    elif s['dropdown'] or s['template_dd']:
        s['dropdown'] = False; s['template_dd'] = False
    elif s['ime']:
        s['ime'] = False; s['focused'] = None
    elif s['panel']:
        s['panel'] = False
    else:
        # ungated BACK finishes this single-activity app (pixel-proven run 42212127418)
        s['running'] = False
        s['foreground'] = 'launcher'

def do_swipe(s, y1, y2):
    scr = s['screen']
    if scr not in SCROLLABLE or s['foreground'] != 'app':
        return
    dy = y1 - y2   # positive: up-swipe, content scrolls down (offset grows)
    if abs(dy) < 300:
        return  # long-press / in-place swipe
    _n, bottom = stack(s, scr)
    max_off = max(0, bottom - (VIEW_BOTTOM - VIEW_TOP))
    sc = s.setdefault('scroll', {})
    sc[scr] = min(max(sc.get(scr, 0) + dy, 0), max_off)

def launch(s):
    if os.environ.get('ND_FAKE_LAUNCH_FAIL') == '1':
        return
    s['launch_count'] = s.get('launch_count', 0) + 1
    lim = os.environ.get('ND_FAKE_LAUNCH_FAIL_AFTER')
    if lim and s['launch_count'] > int(lim):
        return
    if s['version']:
        s['running'] = True
        s['foreground'] = 'app'
        s['screen'] = 'home'
        s['ime'] = False; s['focused'] = None
        s['dropdown'] = False; s['panel'] = False
        s['current_conv'] = None  # cold start: picker header, history in Room
        s['editor'] = None

def main():
    args = sys.argv[1:]
    if not args:
        return 0
    s = load()
    cmd = args[0]
    if cmd == 'install':
        path = args[-1]
        if '-r' in args:
            # in-place upgrade: data preserved, process killed
            s['version'] = '2.0.1' if ('old' in path or '2.0.1' in path) else '2.1'
        else:
            keep = default_state()
            keep['version'] = '2.0.1' if ('old' in path or '2.0.1' in path) else '2.1'
            s = keep
        s['running'] = False; s['foreground'] = 'launcher'; s['ime'] = False
        s['dropdown'] = False; s['panel'] = False; s['notif_dialog'] = False
        save(s)
        print('Success')
        return 0
    if cmd == 'exec-out':
        rest = args[1:]
        if rest[:2] == ['screencap', '-p']:
            sys.stdout.buffer.write(PNG)
            return 0
        if rest[:1] == ['run-as']:
            return 0  # debug-only paths; not exercised in release flow
    if cmd == 'pull':
        src, dst = args[1], args[2]
        rel = src.lstrip('/').replace('storage/emulated/0/', '', 1)
        if not rel.startswith('sdcard'):
            rel = 'sdcard/' + rel
        f = sp(rel)
        if os.path.exists(f):
            shutil.copyfile(f, dst)
            return 0
        print('adb: error: remote object does not exist', file=sys.stderr)
        return 1
    if cmd == 'shell':
        a = args[1:]
        if not a:
            return 0
        if a[0] == 'uiautomator' and a[1] == 'dump':
            out = sp(a[2].lstrip('/'))
            os.makedirs(os.path.dirname(out), exist_ok=True)
            open(out, 'w').write(dump_xml(s))
            print('UI hierchary dumped to: ' + a[2])
            return 0
        if a[0] == 'cat':
            f = sp(a[1].lstrip('/'))
            if os.path.exists(f):
                sys.stdout.write(open(f).read())
                return 0
            return 1
        if a[0] == 'input':
            if a[1] == 'tap':
                do_tap(s, int(a[2]), int(a[3]))
            elif a[1] == 'text':
                ch = unquote(a[2])
                if s['focused'] and s['ime']:
                    if s['focused'].startswith('settings:'):
                        k = {'settings:name': 'userName', 'settings:url': 'backendUrl',
                             'settings:assistant': 'assistantName'}[s['focused']]
                        s['settings'][k] = s['settings'][k] + ch
                    else:
                        s['fields'][s['focused']] = s['fields'].get(s['focused'], '') + ch
            elif a[1] == 'keyevent':
                code = a[-1]
                if code == '4' or code == 'KEYCODE_BACK':
                    do_back(s)
                elif code in ('67', '112'):
                    if s['focused']:
                        if s['focused'].startswith('settings:'):
                            k = {'settings:name': 'userName', 'settings:url': 'backendUrl',
                                 'settings:assistant': 'assistantName'}[s['focused']]
                            s['settings'][k] = ''
                        else:
                            s['fields'][s['focused']] = ''
            elif a[1] == 'swipe':
                do_swipe(s, int(a[3]), int(a[5]))
            save(s)
            return 0
        if a[0] == 'monkey':
            launch(s)
            save(s)
            if s['running']:
                print('Events injected: 1')
                return 0
            print('** No activities found to run, monkey aborted.')
            return 0
        if a[0] == 'am':
            if a[1] == 'force-stop':
                s['running'] = False; s['foreground'] = 'launcher'
                s['ime'] = False; s['dropdown'] = False; s['panel'] = False
            elif a[1] == 'start':
                launch(s)
                if '--es' in a:
                    for i, x in enumerate(a):
                        if x == '--es' and i + 2 < len(a):
                            k, v = a[i + 1], unquote(a[i + 2])
                            if k == 'nd_backend_url':
                                s['settings']['backendUrl'] = v
                            elif k == 'nd_app_token':
                                s['settings']['appToken'] = v
            save(s)
            return 0
        if a[0] == 'pm':
            if a[1] == 'uninstall':
                s = default_state()
            save(s)
            return 0
        if a[0] == 'dumpsys':
            if a[1] == 'window':
                if s['foreground'] == 'app':
                    print('  mCurrentFocus=Window{9d0cf9 u0 %s/%s.MainActivity}' % (PKG, PKG))
                else:
                    print('  mCurrentFocus=Window{8a1 u0 %s/.NexusLauncherActivity}' % LAUNCHER_PKG)
                return 0
            if a[1] == 'input_method':
                print('mInputShown=%s' % ('true' if s['ime'] else 'false'))
                return 0
            if a[1] == 'notification':
                for x in s['notifications']:
                    print(x)
                return 0
        if a[0] == 'ls':
            rel = a[1].lstrip('/').replace('storage/emulated/0/', '', 1)
            if not rel.startswith('sdcard'):
                rel = 'sdcard/' + rel
            d = sp(rel)
            if os.path.isdir(d):
                print('\n'.join(sorted(os.listdir(d))))
            return 0
        if a[0] == 'screencap':
            out = sp(a[2].lstrip('/'))
            os.makedirs(os.path.dirname(out), exist_ok=True)
            open(out, 'wb').write(PNG)
            return 0
        if a[0] in ('settings', 'screenrecord', 'mkdir'):
            return 0
    if cmd == 'logcat':
        return 0
    if cmd == 'uninstall':
        save(default_state())
        print('Success')
        return 0
    return 0

if __name__ == '__main__':
    sys.exit(main())
