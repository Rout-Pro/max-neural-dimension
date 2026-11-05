package com.max.neuraldimension.core.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MeshData(val positions: FloatArray, val normals: FloatArray, val indices: ShortArray)

object Meshes {

    fun sphere(lat: Int, lon: Int): MeshData {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Short>()
        for (i in 0..lat) {
            val theta = Math.PI * i / lat
            val st = sin(theta).toFloat(); val ct = cos(theta).toFloat()
            for (j in 0..lon) {
                val phi = 2.0 * Math.PI * j / lon
                val sp = sin(phi).toFloat(); val cp = cos(phi).toFloat()
                val x = st * cp; val y = ct; val z = st * sp
                pos.add(x); pos.add(y); pos.add(z); nrm.add(x); nrm.add(y); nrm.add(z)
            }
        }
        for (i in 0 until lat) for (j in 0 until lon) {
            val a = i * (lon + 1) + j; val b = a + lon + 1
            idx.add(a.toShort()); idx.add(b.toShort()); idx.add((a + 1).toShort())
            idx.add((a + 1).toShort()); idx.add(b.toShort()); idx.add((b + 1).toShort())
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), idx.toShortArray())
    }

    fun cylinder(segments: Int): MeshData {
        // unit cylinder: radius 1, height 1, centered at origin
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Short>()
        for (j in 0..segments) {
            val phi = 2.0 * Math.PI * j / segments
            val x = cos(phi).toFloat(); val z = sin(phi).toFloat()
            pos.add(x); pos.add(-0.5f); pos.add(z); nrm.add(x); nrm.add(0f); nrm.add(z)
            pos.add(x); pos.add(0.5f); pos.add(z); nrm.add(x); nrm.add(0f); nrm.add(z)
        }
        for (j in 0 until segments) {
            val a = j * 2
            idx.add(a.toShort()); idx.add((a + 1).toShort()); idx.add((a + 2).toShort())
            idx.add((a + 2).toShort()); idx.add((a + 1).toShort()); idx.add((a + 3).toShort())
        }
        // caps
        var base = pos.size / 3
        pos.add(0f); pos.add(0.5f); pos.add(0f); nrm.add(0f); nrm.add(1f); nrm.add(0f)
        for (j in 0..segments) {
            val phi = 2.0 * Math.PI * j / segments
            val x = cos(phi).toFloat(); val z = sin(phi).toFloat()
            pos.add(x); pos.add(0.5f); pos.add(z); nrm.add(0f); nrm.add(1f); nrm.add(0f)
        }
        for (j in 0 until segments) {
            idx.add(base.toShort()); idx.add((base + j + 1).toShort()); idx.add((base + j + 2).toShort())
        }
        base = pos.size / 3
        pos.add(0f); pos.add(-0.5f); pos.add(0f); nrm.add(0f); nrm.add(-1f); nrm.add(0f)
        for (j in 0..segments) {
            val phi = 2.0 * Math.PI * j / segments
            val x = cos(phi).toFloat(); val z = sin(phi).toFloat()
            pos.add(x); pos.add(-0.5f); pos.add(z); nrm.add(0f); nrm.add(-1f); nrm.add(0f)
        }
        for (j in 0 until segments) {
            idx.add(base.toShort()); idx.add((base + j + 2).toShort()); idx.add((base + j + 1).toShort())
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), idx.toShortArray())
    }

    fun torus(segments: Int, tubeSeg: Int, tubeRadius: Float): MeshData {
        // unit torus in XZ plane, ring radius 1
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val idx = ArrayList<Short>()
        for (i in 0..segments) {
            val u = 2.0 * Math.PI * i / segments
            val cu = cos(u).toFloat(); val su = sin(u).toFloat()
            for (j in 0..tubeSeg) {
                val v = 2.0 * Math.PI * j / tubeSeg
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                val x = (1f + tubeRadius * cv) * cu
                val y = tubeRadius * sv
                val z = (1f + tubeRadius * cv) * su
                pos.add(x); pos.add(y); pos.add(z)
                nrm.add(cv * cu); nrm.add(sv); nrm.add(cv * su)
            }
        }
        for (i in 0 until segments) for (j in 0 until tubeSeg) {
            val a = i * (tubeSeg + 1) + j; val b = a + tubeSeg + 1
            idx.add(a.toShort()); idx.add(b.toShort()); idx.add((a + 1).toShort())
            idx.add((a + 1).toShort()); idx.add(b.toShort()); idx.add((b + 1).toShort())
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), idx.toShortArray())
    }

    fun fullscreenTriangle(): MeshData {
        val pos = floatArrayOf(-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f)
        val nrm = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val idx = shortArrayOf(0, 1, 2)
        return MeshData(pos, nrm, idx)
    }

    fun quad(): MeshData {
        val pos = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f)
        val nrm = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val idx = shortArrayOf(0, 1, 2, 0, 2, 3)
        return MeshData(pos, nrm, idx)
    }
}

/** GPU-side mesh. upload() must be called on the GL thread (lazy on first draw).
 * Vertex layout: position(3), normal(3), morph0 delta(3), morph1 delta(3) - 48 bytes. */
class MeshGpu(data: MeshData, morphs: List<FloatArray> = emptyList()) {
    private val interleaved: FloatBuffer
    private val indexBuf: ShortBuffer
    val indexCount = data.indices.size
    private var vao = 0

    init {
        val n = data.positions.size / 3
        val m0 = morphs.getOrNull(0)
        val m1 = morphs.getOrNull(1)
        val inter = FloatArray(n * 12)
        for (i in 0 until n) {
            inter[i * 12] = data.positions[i * 3]
            inter[i * 12 + 1] = data.positions[i * 3 + 1]
            inter[i * 12 + 2] = data.positions[i * 3 + 2]
            inter[i * 12 + 3] = data.normals[i * 3]
            inter[i * 12 + 4] = data.normals[i * 3 + 1]
            inter[i * 12 + 5] = data.normals[i * 3 + 2]
            if (m0 != null) {
                inter[i * 12 + 6] = m0[i * 3]; inter[i * 12 + 7] = m0[i * 3 + 1]; inter[i * 12 + 8] = m0[i * 3 + 2]
            }
            if (m1 != null) {
                inter[i * 12 + 9] = m1[i * 3]; inter[i * 12 + 10] = m1[i * 3 + 1]; inter[i * 12 + 11] = m1[i * 3 + 2]
            }
        }
        interleaved = ByteBuffer.allocateDirect(inter.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        interleaved.put(inter); interleaved.position(0)
        indexBuf = ByteBuffer.allocateDirect(data.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indexBuf.put(data.indices); indexBuf.position(0)
    }

    private fun upload() {
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(2, ids, 1)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, interleaved.capacity() * 4, interleaved, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuf.capacity() * 2, indexBuf, GLES30.GL_STATIC_DRAW)
        val stride = 48
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, stride, 36)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        if (vao == 0) upload()
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }
}
