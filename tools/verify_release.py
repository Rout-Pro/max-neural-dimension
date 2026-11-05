# Verifies BuildConfig constants inside an APK by parsing the dex directly
# (aapt cannot see dex contents). Exits non-zero on any mismatch.
import re, struct, sys, zipfile

apk_path = sys.argv[1]

def parse_buildconfig(data):
    u32 = lambda o: struct.unpack_from('<I', data, o)[0]
    string_ids_off = u32(0x3c); type_ids_off = u32(0x44); field_ids_off = u32(0x54)
    class_defs_size, class_defs_off = u32(0x60), u32(0x64)
    def string_at(idx):
        off = u32(string_ids_off + idx * 4); val, shift, p = 0, 0, off
        while True:
            b = data[p]; p += 1; val |= (b & 0x7f) << shift
            if not b & 0x80: break
            shift += 7
        return data[p:p + val].decode('utf-8', 'replace')
    def type_at(idx): return string_at(u32(type_ids_off + idx * 4))
    def uleb(p):
        val, shift = 0, 0
        while True:
            b = data[p]; p += 1; val |= (b & 0x7f) << shift
            if not b & 0x80: return val, p
            shift += 7
    for c in range(class_defs_size):
        base = class_defs_off + c * 32
        cname = type_at(u32(base))
        if not cname.endswith('BuildConfig;') or 'neuraldimension' not in cname:
            continue
        cdo, svo = u32(base + 24), u32(base + 28)
        p = cdo
        sf, p = uleb(p); inf, p = uleb(p); dm, p = uleb(p); vm, p = uleb(p)
        fidx = 0; fnames = []
        for _ in range(sf):
            diff, p = uleb(p); fidx += diff; acc, p = uleb(p)
            ci, ti, ni = struct.unpack_from('<HHI', data, field_ids_off + fidx * 8)
            fnames.append((string_at(ni), type_at(ti)))
        vals = []
        if svo:
            p = svo; size, p = uleb(p)
            for _ in range(size):
                b = data[p]; p += 1; vt, va = b & 0x1f, b >> 5
                if vt == 0x1f:
                    vals.append(bool(va)); continue
                nb = va + 1; raw = data[p:p + nb]; p += nb
                if vt == 0x17:
                    vals.append(string_at(int.from_bytes(raw, 'little')))
                else:
                    vals.append(int.from_bytes(raw, 'little', signed=True))
        return dict((n, v) for (n, d), v in zip(fnames, vals))
    return None

z = zipfile.ZipFile(apk_path)
bc = None
for name in z.namelist():
    if name.endswith('.dex'):
        blob = z.read(name)
        if b'ND_TEST_HOOK' in blob:
            bc = parse_buildconfig(blob)
            if bc:
                break
if not bc:
    print('::error::BuildConfig with ND_TEST_HOOK not found in any dex')
    sys.exit(1)
print('BuildConfig:', bc)
ok = True
if bc.get('ND_TEST_HOOK') is not False:
    print('::error::ND_TEST_HOOK is not false:', bc.get('ND_TEST_HOOK')); ok = False
if bc.get('BUILD_TYPE') != 'release':
    print('::error::BUILD_TYPE is not release:', bc.get('BUILD_TYPE')); ok = False
if bc.get('VERSION_CODE') != 3:
    print('::error::VERSION_CODE is not 3:', bc.get('VERSION_CODE')); ok = False
sys.exit(0 if ok else 1)
