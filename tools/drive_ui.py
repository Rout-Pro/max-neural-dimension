import re, subprocess, time, sys

def adb(*args):
    return subprocess.run(['adb'] + list(args), capture_output=True, text=True)

def shot(name):
    with open('shots/%s.png' % name, 'wb') as f:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=f)
    print('shot', name); sys.stdout.flush()

def dump(path):
    for _ in range(3):
        adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
        subprocess.run(['adb', 'pull', '/sdcard/ui.xml', path], capture_output=True)
        try:
            return open(path, encoding='utf-8', errors='ignore').read()
        except FileNotFoundError:
            time.sleep(2)
    return ''

def bounds(xml, pattern):
    m = re.search(pattern, xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)

def tap(pt):
    if pt:
        adb('shell', 'input', 'tap', str(pt[0]), str(pt[1]))

B = r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]'

adb('shell', 'monkey', '-p', 'com.max.neuraldimension', '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(14)
shot('01-home')
xml = dump('ui.xml')
tabs = {}
for name in ['Chat', 'Voice', 'Tools', 'Settings']:
    tabs[name] = bounds(xml, r'text="%s"[^>]*bounds="%s"' % (name, B))
print('tabs', tabs); sys.stdout.flush()
for name, png in [('Chat', '02-chat'), ('Voice', '03-voice'), ('Tools', '04-tools'), ('Settings', '05-settings')]:
    tap(tabs.get(name)); time.sleep(3); shot(png)
# back to Chat: send a message; honest starter shows only the user bubble, no fake AI reply
tap(tabs.get('Chat')); time.sleep(3)
xml2 = dump('ui2.xml')
field = bounds(xml2, r'class="android.widget.EditText"[^>]*bounds="%s"' % B)
send = bounds(xml2, r'content-desc="Send"[^>]*bounds="%s"' % B)
print('field', field, 'send', send); sys.stdout.flush()
tap(field); time.sleep(1)
adb('shell', 'input', 'text', 'hello')
time.sleep(1)
adb('shell', 'input', 'keyevent', '111')
time.sleep(1)
tap(send); time.sleep(3)
shot('06-chat-sent')
