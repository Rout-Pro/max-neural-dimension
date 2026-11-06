#!/usr/bin/env python3
"""Fail-fast gate: the INSTALLED package must be the expected candidate before
any feature section runs. A wrong binary under test turns every later
assertion into evidence about the wrong app - run 42285108996: the migration
section left v2.0.1 installed and 26 sections cascaded FAILs against it.

Checks:
  1. versionCode + versionName from `dumpsys package` vs expected (defaults
     mirror app/build.gradle; override with ND_EXPECT_VERSION_CODE/NAME).
  2. signing cert: pull the installed base.apk and compare its SHA-256 cert
     digest with the artifact's (apksigner). Skipped ONLY where the artifact
     or apksigner cannot exist (local harness); unconditional on the runner.

On mismatch: screenshot + CANDIDATE-GATE FAIL + exit 1 (no further driving).
"""
import os, re, subprocess, sys, glob, shutil, tempfile

PKG = 'com.max.neuraldimension'
EXPECT_CODE = os.environ.get('ND_EXPECT_VERSION_CODE', '4')   # app/build.gradle.kts versionCode
EXPECT_NAME = os.environ.get('ND_EXPECT_VERSION_NAME', '2.0')  # app/build.gradle.kts versionName

def adb(*a, text=True):
    return subprocess.run(['adb'] + list(a), capture_output=True, text=text)

def gate_fail(msg):
    os.makedirs('shots', exist_ok=True)
    try:
        png = adb('exec-out', 'screencap', '-p', text=False).stdout
        open('shots/candidate-gate-fail.png', 'wb').write(png)
    except Exception:
        pass
    print('CANDIDATE-GATE FAIL: ' + msg)
    print('ENV-BLOCKED at candidate-gate: installed package is not the expected candidate; aborting before any further driving')
    sys.stdout.flush()
    sys.exit(1)

def main():
    version_only = '--version-only' in sys.argv
    apk = None
    if '--expect-apk' in sys.argv:
        apk = sys.argv[sys.argv.index('--expect-apk') + 1]
    r = adb('shell', 'dumpsys', 'package', PKG)
    m = re.search(r'versionCode=(\d+)', r.stdout or '')
    n = re.search(r'versionName=(\S+)', r.stdout or '')
    if not m:
        gate_fail('package %s not installed (no versionCode in dumpsys package)' % PKG)
    code = m.group(1)
    name = n.group(1) if n else '?'
    if code != EXPECT_CODE or name != EXPECT_NAME:
        gate_fail('installed versionCode=%s versionName=%s, expected %s/%s - wrong binary under test'
                  % (code, name, EXPECT_CODE, EXPECT_NAME))
    print('CANDIDATE-GATE version ok: versionCode=%s versionName=%s' % (code, name))
    sys.stdout.flush()
    if version_only:
        return
    signers = sorted(glob.glob(os.path.join(os.environ.get('ANDROID_HOME', '/nonexistent'),
                                            'build-tools', '*', 'apksigner')))
    which = shutil.which('apksigner')
    if which:
        signers.append(which)
    apksigner = signers[-1] if signers else None
    if not (apk and os.path.exists(apk) and apksigner):
        print('CANDIDATE-GATE cert check skipped: artifact/apksigner unavailable here (version gate still enforced)')
        sys.stdout.flush()
        return
    r = adb('shell', 'pm', 'path', PKG)
    m = re.search(r'package:(\S*base\.apk)', r.stdout or '')
    if not m:
        gate_fail('pm path returned no base.apk for ' + PKG)
    tmp = tempfile.mkdtemp()
    pulled = os.path.join(tmp, 'installed.apk')
    r = adb('pull', m.group(1), pulled)
    if r.returncode != 0 or not os.path.exists(pulled):
        gate_fail('could not pull installed base.apk: ' + ((r.stderr or '').strip()[:120]))
    def cert_sha256(path):
        out = subprocess.run([apksigner, 'verify', '--print-certs', path],
                             capture_output=True, text=True).stdout
        mm = re.search(r'SHA-256 digest:\s*([0-9a-f]+)', out)
        return mm.group(1) if mm else None
    c_inst, c_art = cert_sha256(pulled), cert_sha256(apk)
    if not c_inst or not c_art or c_inst != c_art:
        gate_fail('signing cert mismatch: installed=%s artifact=%s' % (c_inst, c_art))
    print('CANDIDATE-GATE PASS: installed package is the expected candidate (version %s/%s, cert sha256 %s...)'
          % (code, name, c_inst[:16]))
    sys.stdout.flush()

main()
