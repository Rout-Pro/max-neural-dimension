package com.max.neuraldimension.core.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Scene node: transform + material + mesh, hierarchical. */
class Node(val name: String) {
    var mesh: MeshGpu? = null
    val children = ArrayList<Node>()
    var px = 0f; var py = 0f; var pz = 0f
    var rx = 0f; var ry = 0f; var rz = 0f
    var sx = 1f; var sy = 1f; var sz = 1f
    var baseSx = 1f; var baseSy = 1f; var baseSz = 1f
    var rotMat: FloatArray? = null
    var morphNames: List<String> = emptyList()
    var morphW = floatArrayOf(0f, 0f)
    var color = floatArrayOf(0.91f, 0.90f, 0.94f)   // silver default
    var emissive = floatArrayOf(0f, 0f, 0f)
    var alpha = 1f
    var metallic = 0.85f
    var rough = 0.3f
    var transparent = false
    val world = FloatArray(16)
}

class RobotRenderer(private val context: android.content.Context) : GLSurfaceView.Renderer {

    companion object {
        const val VERT = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 aMorph0;
layout(location=3) in vec3 aMorph1;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat3 uNormalMat;
uniform vec2 uMorphW;
out vec3 vNormal;
out vec3 vWorld;
void main() {
    vec3 p = aPos + aMorph0 * uMorphW.x + aMorph1 * uMorphW.y;
    vec4 w = uModel * vec4(p, 1.0);
    vWorld = w.xyz;
    vNormal = uNormalMat * aNormal;
    gl_Position = uMVP * vec4(p, 1.0);
}"""
        const val FRAG = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vWorld;
uniform vec3 uColor;
uniform vec3 uEmissive;
uniform float uAlpha;
uniform vec3 uCamPos;
uniform vec3 uRimColor;
uniform float uMetallic;
uniform float uRough;
out vec4 frag;
void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(vec3(0.45, 0.9, 0.75));
    float diff = max(dot(N, L), 0.0);
    float fill = max(dot(N, normalize(vec3(-0.7, 0.1, 0.4))), 0.0) * 0.35;
    vec3 V = normalize(uCamPos - vWorld);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), mix(110.0, 16.0, uRough));
    float fres = pow(1.0 - max(dot(N, V), 0.0), 2.6);
    vec3 amb = uColor * 0.30;
    vec3 col = amb + uColor * diff * 0.8 + uColor * fill
        + vec3(1.0) * spec * mix(0.15, 0.95, uMetallic)
        + uRimColor * fres * 0.75 + uEmissive;
    frag = vec4(col, uAlpha);
}"""
        const val BG_VERT = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vUV;
void main() { vUV = aPos.xy * 0.5 + 0.5; gl_Position = vec4(aPos, 1.0); }"""
        const val BG_FRAG = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform float uTime;
uniform float uPulse;
out vec4 frag;
void main() {
    vec3 deep = vec3(0.027, 0.020, 0.047);
    vec3 purple = vec3(0.086, 0.043, 0.157);
    vec3 col = mix(purple, deep, pow(vUV.y, 0.75));
    vec2 c = vec2(0.5, 0.42);
    float d = distance(vUV * vec2(0.75, 1.0), c * vec2(0.75, 1.0));
    float glow = exp(-d * 3.2) * (0.35 + 0.10 * sin(uTime * 1.1) + uPulse * 0.35);
    col += vec3(0.506, 0.243, 1.0) * glow * 0.55;
    col += vec3(0.85, 0.73, 1.0) * exp(-d * 9.0) * 0.10;
    frag = vec4(col, 1.0);
}"""
        const val PT_VERT = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP;
uniform float uTime;
out float vTw;
void main() {
    vec3 p = aPos;
    p.y += sin(uTime * 0.25 + aPos.x * 3.1) * 0.12;
    p.x += cos(uTime * 0.18 + aPos.z * 2.3) * 0.10;
    vTw = 0.5 + 0.5 * sin(uTime * (0.7 + fract(aPos.x * 13.7) * 1.3) + aPos.y * 8.0);
    gl_Position = uVP * vec4(p, 1.0);
    gl_PointSize = 2.0 + vTw * 3.5;
}"""
        const val PT_FRAG = """#version 300 es
precision mediump float;
in float vTw;
out vec4 frag;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    float d = dot(c, c);
    if (d > 1.0) discard;
    float a = (1.0 - d) * (1.0 - d) * (0.25 + 0.5 * vTw);
    frag = vec4(vec3(0.80, 0.65, 1.0) * a, 1.0);
}"""
        const val GLOW_VERT = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uCenter;
uniform vec2 uScale;
out vec2 vUV;
void main() {
    vUV = aPos.xy;
    vec4 cv = uView * vec4(uCenter, 1.0);
    cv.xy += aPos.xy * uScale;
    gl_Position = uProj * cv;
}"""
        const val GLOW_FRAG = """#version 300 es
precision mediump float;
in vec2 vUV;
uniform vec3 uGlowColor;
uniform float uIntensity;
out vec4 frag;
void main() {
    float d = length(vUV);
    float a = smoothstep(1.0, 0.0, d);
    a = a * a * a * uIntensity;
    frag = vec4(uGlowColor * a, 1.0);
}"""
    }

    private var prog = 0; private var bgProg = 0; private var ptProg = 0; private var glowProg = 0
    private val proj = FloatArray(16); private val view = FloatArray(16)
    private val mvp = FloatArray(16); private val tmp = FloatArray(16); private val model = FloatArray(16)
    private val normalMat = FloatArray(9)
    private val camPos = floatArrayOf(0f, 0.30f, 4.3f)

    private lateinit var bgTri: MeshGpu
    private lateinit var quad: MeshGpu

    private lateinit var root: Node
    private lateinit var torso: Node
    private lateinit var headGroup: Node
    private lateinit var face: Node
    private lateinit var eyeL: Node
    private lateinit var eyeR: Node
    private lateinit var mouth: Node
    private lateinit var ring: Node
    private lateinit var core: Node
    private lateinit var earL: Node
    private lateinit var earR: Node

    private var starBuf = 0
    private var starCount = 0

    private var startTime = 0L
    private var nextBlink = 2.5f
    private var blinkT = -1f
    private var ampSmooth = 0f
    private var errMix = 0f
    private var pulse = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        startTime = SystemClock.uptimeMillis()
        GLES30.glClearColor(0.027f, 0.020f, 0.047f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        prog = buildProgram(VERT, FRAG)
        bgProg = buildProgram(BG_VERT, BG_FRAG)
        ptProg = buildProgram(PT_VERT, PT_FRAG)
        glowProg = buildProgram(GLOW_VERT, GLOW_FRAG)
        bgTri = MeshGpu(Meshes.fullscreenTriangle())
        quad = MeshGpu(Meshes.quad())
        loadSceneFromAsset()
        buildStars()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 41f, w.toFloat() / h.toFloat(), 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        animate(t)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        drawBackground(t)
        drawStars(t)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        Matrix.setLookAtM(view, 0, camPos[0], camPos[1], camPos[2], 0f, -0.05f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        drawNode(root, model, transparentPass = false)
        GLES30.glDepthMask(false)
        drawNode(root, model, transparentPass = true)
        drawGlows()
        GLES30.glDepthMask(true)
    }

    // ---------------- scene ----------------

    private fun loadSceneFromAsset() {
        val scene = GlbLoader.load(context.assets, "max_robot.glb")
        root = scene.root
        torso = scene.byName.getValue("torso")
        headGroup = scene.byName.getValue("head")
        face = scene.byName.getValue("face")
        eyeL = scene.byName.getValue("eyeL")
        eyeR = scene.byName.getValue("eyeR")
        mouth = scene.byName.getValue("mouth")
        ring = scene.byName.getValue("ring")
        core = scene.byName.getValue("core")
        earL = scene.byName.getValue("earL")
        earR = scene.byName.getValue("earR")
        ring.rx = 0.42f // tilt so its rotation is visible
    }

    private fun f3(a: Float, b: Float, c: Float) = floatArrayOf(a, b, c)

    private fun buildStars() {
        starCount = 130
        val data = FloatArray(starCount * 3)
        for (i in 0 until starCount) {
            data[i * 3] = (Random.nextFloat() * 2f - 1f) * 3.2f
            data[i * 3 + 1] = (Random.nextFloat() * 2f - 1f) * 2.4f + 0.4f
            data[i * 3 + 2] = -1.5f - Random.nextFloat() * 5f
        }
        val buf = java.nio.ByteBuffer.allocateDirect(data.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data); buf.position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0); starBuf = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    // ---------------- animation ----------------

    private fun animate(t: Float) {
        val intensity = if (RobotState.reducedMotion) 0.25f else RobotState.intensity
        val mode = RobotState.mode

        val targetAmp = if (mode == RobotState.Mode.SPEAKING) RobotState.mouthAmp else 0f
        ampSmooth += (targetAmp - ampSmooth) * 0.45f

        // blink scheduling -> drives the eyes' "blink" morph weight
        if (blinkT < 0f && t > nextBlink) { blinkT = t; nextBlink = t + 2.4f + Random.nextFloat() * 3.2f }
        var blinkW = 0f
        if (blinkT >= 0f) {
            val bt = (t - blinkT) / 0.16f
            if (bt >= 1f) blinkT = -1f
            else blinkW = if (bt < 0.5f) bt * 2f else (1f - bt) * 2f
        }

        val idle = sin(t * 1.35f) * intensity
        torso.sy = torso.baseSy * (1f + 0.012f * idle)
        core.emissive = f3(0.42f + 0.12f * sin(t * 2.2f), 0.20f, 0.78f + 0.14f * sin(t * 2.2f))

        headGroup.ry = (0.14f * sin(t * 0.33f) + 0.05f * sin(t * 0.9f)) * intensity
        headGroup.rz = 0.028f * sin(t * 0.5f) * intensity
        headGroup.rx = 0.03f * sin(t * 0.7f) * intensity
        headGroup.py = 0.32f + 0.015f * idle

        ring.ry = t * 0.5f * intensity
        var eyeScale = 1f
        var eyeShift = 0f
        var mouthW = ampSmooth
        var mouthWide = 1f
        pulse = 0f

        when (mode) {
            RobotState.Mode.LISTENING -> {
                eyeScale = 1.18f
                headGroup.rz += 0.05f
                ring.ry = t * 1.6f * intensity
                pulse = 0.25f + 0.2f * sin(t * 4f)
                face.emissive = f3(0.16f, 0.07f, 0.32f)
            }
            RobotState.Mode.THINKING -> {
                eyeShift = 0.035f * sin(t * 2.4f)
                headGroup.rz += 0.10f
                ring.ry = t * 2.6f * intensity
                pulse = 0.3f + 0.25f * sin(t * 7f)
            }
            RobotState.Mode.SPEAKING -> {
                headGroup.rx += 0.045f * sin(t * 6.5f) * ampSmooth
                pulse = ampSmooth * 0.8f
            }
            RobotState.Mode.HAPPY, RobotState.Mode.EXCITED -> {
                blinkW = maxOf(blinkW, 0.38f)
                mouthW = maxOf(mouthW, 0.55f); mouthWide = 1.3f
                headGroup.rz += 0.08f
            }
            RobotState.Mode.CURIOUS -> {
                eyeScale = 1.12f; headGroup.rz += 0.13f
                eyeShift = 0.02f * sin(t * 1.2f)
            }
            RobotState.Mode.CONFUSED -> {
                headGroup.rz += -0.11f
            }
            RobotState.Mode.ERROR -> {
                pulse = 0.5f
            }
            else -> {}
        }

        errMix += ((if (mode == RobotState.Mode.ERROR) 1f else 0f) - errMix) * 0.12f
        val eL = mix(f3(0.75f, 0.55f, 1.15f), f3(1.2f, 0.25f, 0.22f), errMix)
        eyeL.emissive = eL; eyeR.emissive = eL.copyOf()
        mouth.emissive = mix(f3(0.55f, 0.28f, 0.95f), f3(1.1f, 0.2f, 0.2f), errMix)

        eyeL.sx = eyeL.baseSx * eyeScale; eyeL.sy = eyeL.baseSy * eyeScale
        eyeR.sx = eyeR.baseSx * eyeScale; eyeR.sy = eyeR.baseSy * eyeScale
        eyeL.px = -0.175f + eyeShift; eyeR.px = 0.175f + eyeShift
        eyeL.morphW[0] = blinkW; eyeR.morphW[0] = blinkW

        mouth.morphW[0] = mouthW
        mouth.sx = mouth.baseSx * mouthWide
    }

    private fun mix(a: FloatArray, b: FloatArray, m: Float) = f3(
        a[0] + (b[0] - a[0]) * m, a[1] + (b[1] - a[1]) * m, a[2] + (b[2] - a[2]) * m)

    // ---------------- drawing ----------------

    private fun drawNode(n: Node, parent: FloatArray, transparentPass: Boolean) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, n.px, n.py, n.pz)
        val rm = n.rotMat
        if (rm != null) {
            val rot = FloatArray(16)
            Matrix.multiplyMM(rot, 0, model, 0, rm, 0)
            System.arraycopy(rot, 0, model, 0, 16)
        }
        if (n.rz != 0f) Matrix.rotateM(model, 0, n.rz * 57.2958f, 0f, 0f, 1f)
        if (n.ry != 0f) Matrix.rotateM(model, 0, n.ry * 57.2958f, 0f, 1f, 0f)
        if (n.rx != 0f) Matrix.rotateM(model, 0, n.rx * 57.2958f, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, n.sx, n.sy, n.sz)
        Matrix.multiplyMM(n.world, 0, parent, 0, model, 0)

        if (n.mesh != null && n.transparent == transparentPass) {
            Matrix.multiplyMM(mvp, 0, view, 0, n.world, 0)
            val mvp2 = FloatArray(16)
            Matrix.multiplyMM(mvp2, 0, proj, 0, mvp, 0)
            normalFromModel(n.world)
            GLES30.glUseProgram(prog)
            GLES30.glUniformMatrix4fv(loc(prog, "uMVP"), 1, false, mvp2, 0)
            GLES30.glUniformMatrix4fv(loc(prog, "uModel"), 1, false, n.world, 0)
            GLES30.glUniformMatrix3fv(loc(prog, "uNormalMat"), 1, false, normalMat, 0)
            GLES30.glUniform3fv(loc(prog, "uColor"), 1, n.color, 0)
            GLES30.glUniform3fv(loc(prog, "uEmissive"), 1, n.emissive, 0)
            GLES30.glUniform1f(loc(prog, "uAlpha"), n.alpha)
            GLES30.glUniform3fv(loc(prog, "uCamPos"), 1, camPos, 0)
            GLES30.glUniform3f(loc(prog, "uRimColor"), 0.85f, 0.72f, 1f)
            GLES30.glUniform1f(loc(prog, "uMetallic"), n.metallic)
            GLES30.glUniform1f(loc(prog, "uRough"), n.rough)
            GLES30.glUniform2f(loc(prog, "uMorphW"), n.morphW[0], n.morphW[1])
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            n.mesh!!.draw()
        }
        for (c in n.children) drawNode(c, n.world, transparentPass)
    }

    private fun normalFromModel(m: FloatArray) {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]
        val a10 = m[4]; val a11 = m[5]; val a12 = m[6]
        val a20 = m[8]; val a21 = m[9]; val a22 = m[10]
        val c00 = a11 * a22 - a12 * a21
        val c01 = a12 * a20 - a10 * a22
        val c02 = a10 * a21 - a11 * a20
        val det = a00 * c00 + a01 * c01 + a02 * c02
        val id = if (det != 0f) 1f / det else 1f
        normalMat[0] = c00 * id; normalMat[1] = (a02 * a21 - a01 * a22) * id; normalMat[2] = (a01 * a12 - a02 * a11) * id
        normalMat[3] = c01 * id; normalMat[4] = (a00 * a22 - a02 * a20) * id; normalMat[5] = (a02 * a10 - a00 * a12) * id
        normalMat[6] = c02 * id; normalMat[7] = (a01 * a20 - a00 * a21) * id; normalMat[8] = (a00 * a11 - a01 * a10) * id
    }

    private fun drawBackground(t: Float) {
        GLES30.glUseProgram(bgProg)
        GLES30.glUniform1f(loc(bgProg, "uTime"), t)
        GLES30.glUniform1f(loc(bgProg, "uPulse"), pulse)
        GLES30.glDisable(GLES30.GL_BLEND)
        bgTri.draw()
    }

    private fun drawStars(t: Float) {
        GLES30.glUseProgram(ptProg)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES30.glUniformMatrix4fv(loc(ptProg, "uVP"), 1, false, mvp, 0)
        GLES30.glUniform1f(loc(ptProg, "uTime"), t)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, starBuf)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun worldPos(n: Node): FloatArray {
        return floatArrayOf(n.world[12], n.world[13], n.world[14])
    }

    private fun drawGlows() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(glowProg)
        GLES30.glUniformMatrix4fv(loc(glowProg, "uView"), 1, false, view, 0)
        GLES30.glUniformMatrix4fv(loc(glowProg, "uProj"), 1, false, proj, 0)
        glowAt(worldPos(eyeL), 0.22f, 0.22f, eyeL.emissive, 0.55f)
        glowAt(worldPos(eyeR), 0.22f, 0.22f, eyeR.emissive, 0.55f)
        glowAt(worldPos(mouth), 0.30f, 0.12f + ampSmooth * 0.15f, mouth.emissive, 0.35f + ampSmooth * 0.5f)
        glowAt(worldPos(core), 0.42f, 0.42f, core.emissive, 0.5f + 0.2f * pulse)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun glowAt(center: FloatArray, sx: Float, sy: Float, color: FloatArray, intensity: Float) {
        GLES30.glUniform3fv(loc(glowProg, "uCenter"), 1, center, 0)
        GLES30.glUniform2f(loc(glowProg, "uScale"), sx, sy)
        GLES30.glUniform3fv(loc(glowProg, "uGlowColor"), 1, color, 0)
        GLES30.glUniform1f(loc(glowProg, "uIntensity"), intensity)
        quad.draw()
    }

    // ---------------- GL helpers ----------------

    private val locCache = HashMap<Int, HashMap<String, Int>>()
    private fun loc(p: Int, name: String): Int {
        val m = locCache.getOrPut(p) { HashMap() }
        return m.getOrPut(name) { GLES30.glGetUniformLocation(p, name) }
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            throw RuntimeException("shader compile failed: $log")
        }
        return s
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("program link failed: " + GLES30.glGetProgramInfoLog(p))
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }
}
