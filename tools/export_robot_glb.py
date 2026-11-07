#!/usr/bin/env python3
"""Exports the MAX robot as a rigged GLB asset (glTF 2.0 binary).

The asset is the canonical reusable character: named node rig (torso, shoulders,
neck, head, face, eyes, mouth, ears, ring, core) plus morph targets
("mouthOpen" on the mouth, "blink" on each eye) driven at runtime by the app.

Pure stdlib. Run: python3 tools/export_robot_glb.py  -> app/src/main/assets/max_robot.glb
"""
import json, math, os, struct

# ---------- mesh generators (unit primitives, scaled per node) ----------

def sphere(lat=28, lon=36):
    pos, nrm, idx = [], [], []
    for i in range(lat + 1):
        th = math.pi * i / lat
        st, ct = math.sin(th), math.cos(th)
        for j in range(lon + 1):
            ph = 2 * math.pi * j / lon
            sp, cp = math.sin(ph), math.cos(ph)
            x, y, z = st * cp, ct, st * sp
            pos += [x, y, z]; nrm += [x, y, z]
    for i in range(lat):
        for j in range(lon):
            a = i * (lon + 1) + j; b = a + lon + 1
            idx += [a, b, a + 1, a + 1, b, b + 1]
    return pos, nrm, idx

def cylinder(seg=28):
    pos, nrm, idx = [], [], []
    for j in range(seg + 1):
        ph = 2 * math.pi * j / seg
        x, z = math.cos(ph), math.sin(ph)
        pos += [x, -0.5, z]; nrm += [x, 0, z]
        pos += [x, 0.5, z]; nrm += [x, 0, z]
    for j in range(seg):
        a = j * 2
        idx += [a, a + 1, a + 2, a + 2, a + 1, a + 3]
    base = len(pos) // 3
    pos += [0, 0.5, 0]; nrm += [0, 1, 0]
    for j in range(seg + 1):
        ph = 2 * math.pi * j / seg
        pos += [math.cos(ph), 0.5, math.sin(ph)]; nrm += [0, 1, 0]
    for j in range(seg):
        idx += [base, base + j + 1, base + j + 2]
    base = len(pos) // 3
    pos += [0, -0.5, 0]; nrm += [0, -1, 0]
    for j in range(seg + 1):
        ph = 2 * math.pi * j / seg
        pos += [math.cos(ph), -0.5, math.sin(ph)]; nrm += [0, -1, 0]
    for j in range(seg):
        idx += [base, base + j + 2, base + j + 1]
    return pos, nrm, idx

def torus(seg=40, tube=14, tr=0.085):
    pos, nrm, idx = [], [], []
    for i in range(seg + 1):
        u = 2 * math.pi * i / seg
        cu, su = math.cos(u), math.sin(u)
        for j in range(tube + 1):
            v = 2 * math.pi * j / tube
            cv, sv = math.cos(v), math.sin(v)
            pos += [(1 + tr * cv) * cu, tr * sv, (1 + tr * cv) * su]
            nrm += [cv * cu, sv, cv * su]
    for i in range(seg):
        for j in range(tube):
            a = i * (tube + 1) + j; b = a + tube + 1
            idx += [a, b, a + 1, a + 1, b, b + 1]
    return pos, nrm, idx

# ---------- GLB writer ----------

class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.buffer_views = []
        self.accessors = []
        self.materials = []
        self.meshes = []
        self.nodes = []

    def add_buffer_view(self, data: bytes, target: int) -> int:
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        self.buffer_views.append({"buffer": 0, "byteOffset": off, "byteLength": len(data), "target": target})
        return len(self.buffer_views) - 1

    def add_floats(self, floats, type_="VEC3"):
        data = struct.pack("<%df" % len(floats), *floats)
        bv = self.add_buffer_view(data, 34962)
        acc = {"bufferView": bv, "componentType": 5126, "count": len(floats) // 3, "type": type_}
        if type_ == "VEC3":
            xs = floats[0::3]; ys = floats[1::3]; zs = floats[2::3]
            acc["min"] = [min(xs), min(ys), min(zs)]
            acc["max"] = [max(xs), max(ys), max(zs)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def add_indices(self, indices):
        data = struct.pack("<%dH" % len(indices), *indices)
        bv = self.add_buffer_view(data, 34963)
        self.accessors.append({"bufferView": bv, "componentType": 5123, "count": len(indices), "type": "SCALAR"})
        return len(self.accessors) - 1

    def add_material(self, name, base, metallic, rough, emissive=None, alpha_mode=None):
        m = {"name": name, "pbrMetallicRoughness": {
            "baseColorFactor": base, "metallicFactor": metallic, "roughnessFactor": rough}}
        if emissive:
            m["emissiveFactor"] = emissive
        if alpha_mode:
            m["alphaMode"] = alpha_mode
        self.materials.append(m)
        return len(self.materials) - 1

    def add_mesh(self, name, pos, nrm, idx, material, morphs=None):
        """morphs: list of (target_name, position_deltas)"""
        prim = {"attributes": {"POSITION": self.add_floats(pos), "NORMAL": self.add_floats(nrm)},
                "indices": self.add_indices(idx), "material": material}
        mesh = {"name": name, "primitives": [prim]}
        if morphs:
            targets = []
            names = []
            for tname, deltas in morphs:
                targets.append({"POSITION": self.add_floats(deltas)})
                names.append(tname)
            prim["targets"] = targets
            mesh["extras"] = {"targetNames": names}
        self.meshes.append(mesh)
        return len(self.meshes) - 1

    def add_node(self, name, mesh=None, children=None, t=None, r=None, s=None, weights=None):
        n = {"name": name}
        if mesh is not None: n["mesh"] = mesh
        if children: n["children"] = children
        if t: n["translation"] = t
        if r: n["rotation"] = r
        if s: n["scale"] = s
        if weights: n["weights"] = weights
        self.nodes.append(n)
        return len(self.nodes) - 1

    def write(self, path, root=0):
        gltf = {
            "asset": {"version": "2.0", "generator": "max-export_robot_glb.py"},
            "scene": 0,
            "scenes": [{"nodes": [root], "name": "Scene"}],
            "nodes": self.nodes,
            "meshes": self.meshes,
            "materials": self.materials,
            "accessors": self.accessors,
            "bufferViews": self.buffer_views,
            "buffers": [{"byteLength": len(self.bin)}],
        }
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        binb = bytes(self.bin)
        while len(binb) % 4:
            binb += b"\x00"
        total = 12 + 8 + len(js) + 8 + len(binb)
        with open(path, "wb") as f:
            f.write(struct.pack("<4sII", b"glTF", 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A))  # JSON
            f.write(js)
            f.write(struct.pack("<II", len(binb), 0x004E4942))  # BIN
            f.write(binb)
        print("wrote", path, os.path.getsize(path), "bytes")

# ---------- build the robot ----------

SILVER = [0.92, 0.91, 0.95, 1.0]
SILVER2 = [0.86, 0.85, 0.90, 1.0]
GREY = [0.72, 0.70, 0.78, 1.0]
FACE = [0.13, 0.05, 0.24, 0.97]
EYE = [0.85, 0.73, 1.0, 1.0]
NEON = [0.65, 0.36, 1.0, 1.0]
RING = [0.50, 0.24, 1.0, 0.85]

def morph_scale_y(pos, factor):
    d = []
    for i in range(0, len(pos), 3):
        d += [0.0, pos[i + 1] * (factor - 1.0), 0.0]
    return d

def morph_smile(pos, k=2.5, center_dip=0.35):
    # Smile arc: mouth corners (|x| extremes) rise along a parabola while the
    # center dips slightly. Applied as a vertex shift, so the mouth line bends
    # without changing its silhouette thickness.
    d = []
    for i in range(0, len(pos), 3):
        x = pos[i]
        d += [0.0, k * (x * x - center_dip), 0.0]
    return d

def main():
    g = Glb()
    m_silver = g.add_material("ceramic_silver", SILVER, 0.85, 0.25)
    m_silver2 = g.add_material("ceramic_silver_soft", SILVER2, 0.9, 0.3)
    m_grey = g.add_material("joint_grey", GREY, 0.95, 0.35)
    m_face = g.add_material("glass_face", FACE, 0.35, 0.06, emissive=[0.16, 0.05, 0.30], alpha_mode="BLEND")
    m_eye = g.add_material("eye_glow", EYE, 0.0, 0.5, emissive=[0.75, 0.55, 1.0])
    m_mouth = g.add_material("mouth_glow", NEON, 0.0, 0.5, emissive=[0.55, 0.28, 0.95])
    m_core = g.add_material("core_glow", NEON, 0.0, 0.4, emissive=[0.50, 0.24, 0.90])
    m_ring = g.add_material("holo_ring", RING, 0.2, 0.4, emissive=[0.38, 0.16, 0.72], alpha_mode="BLEND")

    sp = sphere()
    cyl = cylinder()
    tor = torus()

    mesh_body = g.add_mesh("body_sphere", *sp, m_silver)
    mesh_body2 = g.add_mesh("body_sphere_soft", *sp, m_silver2)
    mesh_neck = g.add_mesh("neck_cyl", *cyl, m_grey)
    mesh_face = g.add_mesh("face_glass", *sp, m_face)
    mesh_ring = g.add_mesh("ring_torus", *tor, m_ring)

    # eyes: sphere + "blink" morph (flatten Y)
    eye_pos = sp[0]
    mesh_eye = g.add_mesh("eye", sp[0], sp[1], sp[2], m_eye,
                          morphs=[("blink", morph_scale_y(eye_pos, 0.08))])
    # mouth: sphere + "mouthOpen" morph (stretch Y) + "smile" morph (corner arc)
    mesh_mouth = g.add_mesh("mouth", sp[0], sp[1], sp[2], m_mouth,
                            morphs=[("mouthOpen", morph_scale_y(eye_pos, 4.2)),
                                    ("smile", morph_smile(eye_pos))])
    mesh_core = g.add_mesh("core", *sp, m_core)

    n_torso = g.add_node("torso", mesh_body, t=[0, -1.62, 0], s=[0.98, 0.98, 0.72])
    n_shoulder_l = g.add_node("shoulderL", mesh_body2, t=[-0.82, -1.05, 0], s=[0.34, 0.40, 0.34])
    n_shoulder_r = g.add_node("shoulderR", mesh_body2, t=[0.82, -1.05, 0], s=[0.34, 0.40, 0.34])
    n_neck = g.add_node("neck", mesh_neck, t=[0, -0.45, 0], s=[0.20, 0.56, 0.20])

    n_skull = g.add_node("skull", mesh_body, t=[0, 0.12, 0], s=[0.56, 0.64, 0.58])
    n_face = g.add_node("face", mesh_face, t=[0, 0.10, 0.235], s=[0.465, 0.55, 0.375])
    n_eye_l = g.add_node("eyeL", mesh_eye, t=[-0.175, 0.16, 0.55], s=[0.062, 0.085, 0.03], weights=[0.0])
    n_eye_r = g.add_node("eyeR", mesh_eye, t=[0.175, 0.16, 0.55], s=[0.062, 0.085, 0.03], weights=[0.0])
    n_mouth = g.add_node("mouth", mesh_mouth, t=[0, -0.155, 0.55], s=[0.115, 0.018, 0.02], weights=[0.0, 0.0])
    n_ear_l = g.add_node("earL", mesh_neck, t=[-0.575, 0.10, 0], r=[0, 0, math.sin(math.pi / 2), math.cos(math.pi / 2)], s=[0.10, 0.05, 0.10])
    n_ear_r = g.add_node("earR", mesh_neck, t=[0.575, 0.10, 0], r=[0, 0, math.sin(math.pi / 2), math.cos(math.pi / 2)], s=[0.10, 0.05, 0.10])
    n_head = g.add_node("head", children=[n_skull, n_face, n_eye_l, n_eye_r, n_mouth, n_ear_l, n_ear_r], t=[0, 0.32, 0])

    n_core = g.add_node("core", mesh_core, t=[0, -0.85, 0.60], s=[0.11, 0.11, 0.05])
    n_ring = g.add_node("ring", mesh_ring, t=[0, -2.28, 0], s=[0.95, 0.95, 0.95])

    n_root = g.add_node("max_robot", children=[n_torso, n_shoulder_l, n_shoulder_r, n_neck, n_head, n_core, n_ring])

    os.makedirs("app/src/main/assets", exist_ok=True)
    g.write("app/src/main/assets/max_robot.glb", root=n_root)

if __name__ == "__main__":
    main()
