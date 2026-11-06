#!/usr/bin/env python3
"""Local integration test of the ACTUAL release harness entrypoint:
sh -c 'bash tools/run_ui_pass.sh' (the exact android-emulator-runner@v2 line)
against tools/fake_adb.py (deterministic device model) + stub curl.

Scenarios:
  T1 happy        - full release sections; rc 0, every required assert PASS, no FAIL
  T2 no-relaunch  - mutant driver without the relaunch+foreground gate; must FAIL,
                    and launcher label must NOT satisfy app-UI assertions
  T3 no-create    - fake device refuses project creation; zero-project deletion
                    must NOT pass; job must FAIL
  T4 launch-fail  - app never foregrounds after install -r; fixed driver must fail
                    FAST with env-blocked pixel, no assertion cascade
  T5 transport    - stderr propagation + nonzero rc through sh -c + run_ui_pass.sh
  T6 workflow     - the checked-in workflow script input is a single line
  T8 no-ime-dismiss - mutant driver whose ensure_visible never drops the IME;
                    must reproduce run 42267432732 (swipes eaten in keyboard band)
  T9 weak-seed    - mutant seed that never clears; the driver's EXACT field
                    equality must FAIL on the merged 'RoutSeedUserV1'
  T10 clear-resisted - fake field refuses to clear (ND_FAKE_IGNORE_CLEAR); the
                    seed must FAIL honestly, never type into a non-empty field
"""
import os, subprocess, sys, shutil, time, json

ND = '/tmp/nd'
BASE = '/tmp/nd-itest'
BIN = os.path.join(BASE, 'bin')
SECTIONS = 'upgrade,weather,habits,remind,device,studio'

def setup_shims():
    os.makedirs(BIN, exist_ok=True)
    adb = os.path.join(BIN, 'adb')
    if os.path.lexists(adb):
        os.remove(adb)
    os.symlink(os.path.join(ND, 'tools', 'fake_adb.py'), adb)
    with open(os.path.join(BIN, 'curl'), 'w') as f:
        f.write('#!/bin/sh\nwhile [ $# -gt 0 ]; do\n  case "$1" in\n    -o) echo fake-apk-bytes > "$2"; shift 2;;\n    *) shift;;\n  esac\ndone\nexit 0\n')
    os.chmod(os.path.join(BIN, 'curl'), 0o755)

def run_scenario(name, sections, mutant=None, extra_env=None):
    d = os.path.join(BASE, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(os.path.join(d, 'state'))
    os.chdir(ND)
    shutil.rmtree(os.path.join(ND, 'shots'), ignore_errors=True)
    orig = open(os.path.join(ND, 'tools', 'drive_ui.py')).read()
    orig_seed = open(os.path.join(ND, 'tools', 'seed_upgrade.py')).read()
    if mutant == 'weak-seed':
        # pre-verification seed: types without clearing -> merged value must be
        # caught by the driver's exact field equality (run 42267432732)
        block = "    if not clear_field_verified('Your name'):"
        assert block in orig_seed, 'verified-clear block not found for mutant'
        open(os.path.join(ND, 'tools', 'seed_upgrade.py'), 'w').write(
            orig_seed.replace(block, '    if False:  # MUTANT weak-seed: no clear, no verify', 1))
    elif mutant == 'no-ime-dismiss':
        # pre-IME-fix driver: ensure_visible scrolls with the keyboard open
        block = '    dismiss_keyboard()\n    for _ in range(max_swipes):'
        assert block in orig, 'ensure_visible dismiss block not found for mutant'
        open(os.path.join(ND, 'tools', 'drive_ui.py'), 'w').write(
            orig.replace(block, '    # MUTANT: no IME dismiss before scrolling\n    for _ in range(max_swipes):', 1))
    if mutant == 'no-relaunch':
        block = ("relaunch()\n_r = adb('shell', 'dumpsys', 'window', 'displays')\n"
                 "if PKG not in (_r.stdout or ''):\n    env_blocked('app-not-foreground-after-relaunch')")
        assert block in orig, 'relaunch block not found for mutant'
        open(os.path.join(ND, 'tools', 'drive_ui.py'), 'w').write(
            orig.replace(block, '# MUTANT: relaunch + foreground gate removed'))
    elif mutant == 'no-tools-top':
        # pre-scroll-fix driver: sections inherit prior scroll position
        mutant_src = orig.replace('\n    tools_top()', '')
        assert mutant_src.count('tools_top()') == 1, 'def line only; call sites must all be stripped'
        open(os.path.join(ND, 'tools', 'drive_ui.py'), 'w').write(mutant_src)
    elif mutant == 'stderr':
        open(os.path.join(ND, 'tools', 'drive_ui.py'), 'w').write(
            orig.replace("if not settle(120):",
                         "import sys as _s; _s.stderr.write('TRACE-SENTINEL\\n'); _s.exit(3)\nif not settle(120):", 1))
    env = dict(os.environ)
    env['PATH'] = BIN + ':' + env['PATH']
    env['ND_FAKE_STATE'] = os.path.join(d, 'state')
    env['BUILD_TYPE'] = 'release'
    env['ND_RELEASE_GATE'] = '1'
    env['ND_SECTIONS'] = sections
    env.pop('ND_TEST_BACKEND_URL', None)
    env.pop('ND_TEST_APP_TOKEN', None)
    if extra_env:
        env.update(extra_env)
    t0 = time.time()
    with open(os.path.join(d, 'out.log'), 'w') as fo, open(os.path.join(d, 'err.log'), 'w') as fe:
        p = subprocess.run(['sh', '-c', 'bash tools/run_ui_pass.sh'], cwd=ND, env=env,
                           stdout=fo, stderr=fe)
    dur = time.time() - t0
    if mutant:
        open(os.path.join(ND, 'tools', 'drive_ui.py'), 'w').write(orig)
        open(os.path.join(ND, 'tools', 'seed_upgrade.py'), 'w').write(orig_seed)
    if os.path.isdir(os.path.join(ND, 'shots')):
        shutil.move(os.path.join(ND, 'shots'), os.path.join(d, 'shots'))
    open(os.path.join(d, 'rc'), 'w').write(str(p.returncode))
    out = open(os.path.join(d, 'out.log')).read()
    err = open(os.path.join(d, 'err.log')).read()
    return {'rc': p.returncode, 'out': out, 'err': err, 'dur': dur, 'dir': d}

def lines_starting(out, prefix):
    return [l for l in out.splitlines() if l.startswith(prefix)]

T1_REQUIRED = [
    'PASS expect app-text: NEURAL DIMENSION',
    'PASS expect app-text: AI STATUS',
    'PASS expect app-text: TASKS',
    'PASS expect text: Provider',
    'PASS expect text: New conversation',
    'PASS startup chat: honest backend response rendered (setup card or request failure)',
    'PASS upgrade(release): seeded task visible in UI after migration',
    'PASS upgrade(release): seeded conversation in picker + message rendered',
    'PASS upgrade(release): seeded profile name visible in Settings',
    'PASS upgrade(release): seeded backend URL visible in Settings',
    'PASS weather: briefing card rendered',
    'PASS weather: briefing aggregates local data',
    'PASS weather: real Open-Meteo result rendered (network-dependent)',
    'PASS habits: habit listed',
    'PASS habits: streak becomes 1 (observed state change)',
    'PASS habits: expense listed',
    'PASS habits: total updated (observed state change)',
    'PASS remind: note listed',
    'PASS remind: reminder listed',
    'PASS remind: system notification posted (observed effect)',
    'PASS device: battery shown',
    'PASS device: storage shown',
    'PASS device: flashlight card present',
    'PASS device: RAM shown',
    'PASS device: diagnostics (model/uptime)',
    'PASS device: open apps lists launchable apps',
    'PASS device: media volume control',
    'PASS device: notifications shortcut',
    'PASS device: camera shortcut',
    'PASS studio: empty state',
    'PASS studio: honest labeling (sandbox + CDN note, no offline claim)',
    'PASS studio: project opened in editor',
    'PASS studio: preview renders template heading',
    'PASS studio: console captured script log',
    'PASS studio: export notice shown',
    'PASS studio: exported file exists in Downloads',
    'PASS studio: exported bytes have CSS inlined',
    'PASS studio: exported bytes have JS inlined',
    'PASS studio: exported bytes have no leftover local refs',
    'PASS studio: react export keeps CDN external',
    'PASS studio: react export preserves text/babel',
    'PASS studio: react export inlines local js',
    'PASS studio: project listed',
    'PASS studio: first project deleted',
    'PASS studio: both projects deleted (after creation proven)',
]

results = {}

def check(name, ok, detail):
    detail = str(detail)
    results.setdefault('checks', []).append({'name': name, 'ok': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ' -- ' + detail, flush=True)

SKIP_T1 = '--skip-t1' in sys.argv
ONLY = None
for a in sys.argv:
    if a.startswith('--only='):
        ONLY = set(a.split('=', 1)[1].split(','))
def want_run(tag):
    return ONLY is None or tag in ONLY

def load_prior(name):
    d = os.path.join(BASE, name)
    return {'rc': int(open(os.path.join(d, 'rc')).read().strip()),
            'out': open(os.path.join(d, 'out.log')).read(),
            'err': open(os.path.join(d, 'err.log')).read(),
            'dur': 0.0, 'dir': d}

setup_shims()

# ---- T6: workflow transport regression (cheap, first) ----
wf = open(os.path.join(ND, '.github/workflows/ui-screenshot.yml')).read()
slines = [l.split('script:', 1)[1].strip() for l in wf.splitlines() if l.strip().startswith('script:')]
check('T6 workflow script is a single line invoking the checked-in harness',
      slines == ['bash tools/run_ui_pass.sh'], repr(slines))

# ---- T1 happy path ----
if want_run('T1') and SKIP_T1:
    r = load_prior('T1-happy')
    check('T1 rc=0', r['rc'] == 0, 'rc=%d (prior run, reused logs)' % r['rc'])
elif want_run('T1'):
    r = run_scenario('T1-happy', SECTIONS)
    check('T1 rc=0', r['rc'] == 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
if want_run('T1'):
  check('T1 seed all true', 'SEED-RESULT: task=True msg=True name=True url=True' in r['out'],
      lines_starting(r['out'], 'SEED-RESULT')[:1])
  fails = lines_starting(r['out'], 'FAIL ')
  check('T1 zero FAIL lines', not fails, fails[:5])
  missing = [t for t in T1_REQUIRED if t not in r['out']]
  check('T1 all %d required PASSes present' % len(T1_REQUIRED), not missing, missing[:5])
  check('T1 no assertion-gate trip', 'ASSERTION-GATE' not in r['out'], '')
  for sh_ in ['01-home-robot.png', 'upgrade-release-task.png', 'upgrade-release-chat.png',
              'upgrade-release-settings.png', 'p6-weather-briefing.png', 'p6-reminders.png',
              'p4-studio-editor.png', 'p5-tools-device.png']:
      check('T1 shot %s exists' % sh_, os.path.exists(os.path.join(r['dir'], 'shots', sh_)), '')
  check('T1 honest-state print', 'chat honest state:' in r['out'],
        [l for l in r['out'].splitlines() if 'honest state' in l])
  _st = json.load(open(os.path.join(r['dir'], 'state', 'state.json')))
  check('T7 seeded name EXACT (clear-before-type; default Rout must not merge)',
        _st['settings']['userName'] == 'SeedUserV1', _st['settings']['userName'])
  check('T1 IME-gated BACK safety: zero ungated app exits',
        _st.get('back_exits', 0) == 0, 'back_exits=%s' % _st.get('back_exits'))

# ---- T1n mutant: pre-scroll-fix driver must FAIL on scroll geometry ----
if want_run('T1n'):
  r = run_scenario('T1n-no-scroll-fix', SECTIONS, mutant='no-tools-top')
  check('T1n rc!=0', r['rc'] != 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T1n reproduces run 42248503219: notes above viewport unreachable',
        'FAIL remind: note listed' in r['out'] and 'MISS tap: Add a note...' in r['out'], '')
  check('T1n reproduces run 42248503219: flashlight above viewport unreachable',
        'FAIL device: flashlight card present' in r['out'], '')

# ---- T2 mutant: no relaunch gate ----
if want_run('T2'):
  r = run_scenario('T2-no-relaunch', SECTIONS, mutant='no-relaunch')
  check('T2 rc!=0', r['rc'] != 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T2 app-text NEURAL DIMENSION FAILs (launcher label cannot satisfy)',
        'FAIL expect app-text: NEURAL DIMENSION' in r['out']
        and 'PASS expect app-text: NEURAL DIMENSION' not in r['out'], '')
  check('T2 assertion gate fails job', 'ASSERTION-GATE' in r['out'],
        [l for l in r['out'].splitlines() if 'ASSERTION-GATE' in l][:1])

# ---- T3 mutant: project creation never happens ----
if want_run('T3'):
  r = run_scenario('T3-no-create', 'studio', extra_env={'ND_FAKE_NO_CREATE': '1'})
  check('T3 rc!=0', r['rc'] != 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T3 creation failure recorded', 'FAIL studio: project opened in editor' in r['out'], '')
  check('T3 project listed FAILs', 'FAIL studio: project listed' in r['out'], '')
  check('T3 zero-project deletion must NOT pass',
        'FAIL studio: both projects deleted (after creation proven)' in r['out']
        and 'PASS studio: both projects deleted' not in r['out'], '')

# ---- T4 lost foreground after install -r: fail fast with pixel ----
if want_run('T4'):
  r = run_scenario('T4-launch-fail', SECTIONS, extra_env={'ND_FAKE_LAUNCH_FAIL_AFTER': '2'})
  check('T4 seed still passed', 'SEED-RESULT: task=True msg=True name=True url=True' in r['out'], '')
  check('T4 rc=1', r['rc'] == 1, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T4 ENV-BLOCKED message', 'ENV-BLOCKED at app-not-foreground-after-relaunch' in r['out'], '')
  check('T4 env-blocked pixel exists',
        os.path.exists(os.path.join(r['dir'], 'shots', 'env-blocked-app-not-foreground-after-relaunch.png')), '')
  cascade = lines_starting(r['out'], 'FAIL ')
  check('T4 no assertion cascade', not cascade, cascade[:5])
  check('T4 fast (under 240s incl seed)', r['dur'] < 240, '%.0fs' % r['dur'])

# ---- T5 stderr / rc propagation through sh -c + run_ui_pass.sh ----
if want_run('T5'):
  r = run_scenario('T5-stderr', SECTIONS, mutant='stderr')
  check('T5 driver rc 3 propagates', r['rc'] == 3, 'rc=%d' % r['rc'])
  check('T5 stderr reaches transport', 'TRACE-SENTINEL' in r['err'], r['err'][:200])

# ---- T8 mutant: pre-IME-fix driver must reproduce run 42267432732 ----
if want_run('T8'):
  r = run_scenario('T8-no-ime-dismiss', 'habits,remind', mutant='no-ime-dismiss')
  check('T8 rc!=0', r['rc'] != 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T8 reproduces 42267432732: keyboard eats swipes, expenses unreachable',
        'MISS tap: Expense label...' in r['out'] and 'FAIL habits: expense listed' in r['out'], '')
  check('T8 reproduces 42267432732: reminder title unreachable under open IME',
        'MISS tap: Reminder title...' in r['out'] and 'FAIL remind: reminder listed' in r['out'], '')

# ---- T9 mutant: weak seed -> merged value must FAIL the exact assert ----
if want_run('T9'):
  r = run_scenario('T9-weak-seed', 'upgrade', mutant='weak-seed')
  check('T9 rc!=0 (exact-field mismatch must fail)', r['rc'] != 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T9 exact name assert FAILs on merged value',
        'FAIL upgrade(release): seeded profile name visible in Settings' in r['out'], '')
  check('T9 merged value printed for the record',
        'name field exact value: <RoutSeedUserV1>' in r['out'],
        [l for l in r['out'].splitlines() if 'name field exact value' in l])
  check('T9 URL exact assert still passes (empty default typed cleanly)',
        'PASS upgrade(release): seeded backend URL visible in Settings' in r['out'], '')

# ---- T10: field resists clearing -> seed must fail honestly, never merge ----
if want_run('T10'):
  r = run_scenario('T10-clear-resisted', 'upgrade', extra_env={'ND_FAKE_IGNORE_CLEAR': '1'})
  check('T10 rc!=0 (seed refuses unverifiable field)', r['rc'] != 0, 'rc=%d dur=%.0fs' % (r['rc'], r['dur']))
  check('T10 seed reports field not verifiably empty',
        'not verifiably empty' in r['out'],
        [l for l in r['out'].splitlines() if 'SEED-RESULT' in l])
  check('T10 seed never claimed name=True', 'name=True' not in r['out'], '')

ok = all(c['ok'] for c in results['checks'])
print('ITEST-RESULT: %s (%d/%d checks passed)' % ('ALL-PASS' if ok else 'FAILURES',
      sum(1 for c in results['checks'] if c['ok']), len(results['checks'])), flush=True)
json.dump(results, open(os.path.join(BASE, 'results.json'), 'w'), indent=1)
