package com.max.neuraldimension.core.gl

import android.content.res.AssetManager
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal glTF 2.0 binary (GLB) loader for the MAX robot asset.
 * Supports: node hierarchy with TRS (quaternion rotation), one primitive per mesh
 * with POSITION/NORMAL/indices (u16), PBR metallic-roughness materials with
 * emissive + alpha mode, and up to 2 morph targets (POSITION deltas) per mesh.
 */
object GlbLoader {

    class LoadedScene(val root: Node, val byName: Map<String, Node>)

    fun load(assets: AssetManager, path: String): LoadedScene {
        val bytes = assets.open(path).readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        require(magic == 0x46546C67) { "not a GLB file" }
        buf.int // version
        buf.int // total length
        val jsonLen = buf.int
        val jsonType = buf.int
        require(jsonType == 0x4E4F534A) { "first chunk not JSON" }
        val jsonBytes = ByteArray(jsonLen)
        buf.get(jsonBytes)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val binLen = buf.int
        val binType = buf.int
        require(binType == 0x004E4942) { "second chunk not BIN" }
        val binStart = buf.position()
        val bin = ByteArray(binLen)
        buf.get(bin)

        val accessors = json.getJSONArray("accessors")
        val bufferViews = json.getJSONArray("bufferViews")

        fun readFloats(accIdx: Int): FloatArray {
            val acc = accessors.getJSONObject(accIdx)
            val bv = bufferViews.getJSONObject(acc.getInt("bufferView"))
            val count = acc.getInt("count")
            val comps = when (acc.getString("type")) {
                "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 3
            }
            val base = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val out = FloatArray(count * comps)
            val bb = ByteBuffer.wrap(bin, base, count * comps * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in out.indices) out[i] = bb.float
            return out
        }

        fun readShorts(accIdx: Int): ShortArray {
            val acc = accessors.getJSONObject(accIdx)
            val bv = bufferViews.getJSONObject(acc.getInt("bufferView"))
            val count = acc.getInt("count")
            val base = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val out = ShortArray(count)
            val bb = ByteBuffer.wrap(bin, base, count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in out.indices) out[i] = bb.short
            return out
        }

        val materials = json.getJSONArray("materials")
        fun materialAt(idx: Int): MaterialSpec {
            val m = materials.getJSONObject(idx)
            val pbr = m.optJSONObject("pbrMetallicRoughness")
            val base = pbr?.optJSONArray("baseColorFactor")
            val em = m.optJSONArray("emissiveFactor")
            return MaterialSpec(
                color = if (base != null) floatArrayOf(
                    base.getDouble(0).toFloat(), base.getDouble(1).toFloat(), base.getDouble(2).toFloat())
                    else floatArrayOf(1f, 1f, 1f),
                alpha = if (base != null && base.length() > 3) base.getDouble(3).toFloat() else 1f,
                metallic = pbr?.optDouble("metallicFactor", 1.0)?.toFloat() ?: 1f,
                rough = pbr?.optDouble("roughnessFactor", 1.0)?.toFloat() ?: 1f,
                emissive = if (em != null) floatArrayOf(
                    em.getDouble(0).toFloat(), em.getDouble(1).toFloat(), em.getDouble(2).toFloat())
                    else floatArrayOf(0f, 0f, 0f),
                transparent = m.optString("alphaMode") == "BLEND"
            )
        }

        val meshesJson = json.getJSONArray("meshes")
        val meshCache = HashMap<Int, Pair<MeshGpu, List<String>>>()
        fun meshAt(idx: Int): Pair<MeshGpu, List<String>> {
            meshCache[idx]?.let { return it }
            val mesh = meshesJson.getJSONObject(idx)
            val prim = mesh.getJSONArray("primitives").getJSONObject(0)
            val attrs = prim.getJSONObject("attributes")
            val pos = readFloats(attrs.getInt("POSITION"))
            val nrm = readFloats(attrs.getInt("NORMAL"))
            val idxArr = readShorts(prim.getInt("indices"))
            val morphNames = ArrayList<String>()
            val morphDeltas = ArrayList<FloatArray>()
            val targets = prim.optJSONArray("targets")
            if (targets != null) {
                val names = mesh.optJSONObject("extras")?.optJSONArray("targetNames")
                for (i in 0 until minOf(targets.length(), 2)) {
                    morphDeltas.add(readFloats(targets.getJSONObject(i).getInt("POSITION")))
                    morphNames.add(names?.optString(i) ?: "morph$i")
                }
            }
            val gpu = MeshGpu(MeshData(pos, nrm, idxArr), morphDeltas)
            val pair = gpu to morphNames
            meshCache[idx] = pair
            return pair
        }

        val nodesJson = json.getJSONArray("nodes")
        val byName = HashMap<String, Node>()
        fun buildNode(idx: Int): Node {
            val nj = nodesJson.getJSONObject(idx)
            val n = Node(nj.optString("name", "node$idx"))
            nj.optJSONArray("translation")?.let {
                n.px = it.getDouble(0).toFloat(); n.py = it.getDouble(1).toFloat(); n.pz = it.getDouble(2).toFloat()
            }
            nj.optJSONArray("scale")?.let {
                n.sx = it.getDouble(0).toFloat(); n.sy = it.getDouble(1).toFloat(); n.sz = it.getDouble(2).toFloat()
                n.baseSx = n.sx; n.baseSy = n.sy; n.baseSz = n.sz
            }
            nj.optJSONArray("rotation")?.let {
                n.rotMat = quatToMat(
                    it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                    it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
            }
            nj.optJSONArray("weights")?.let { w ->
                for (i in 0 until minOf(w.length(), 2)) n.morphW[i] = w.getDouble(i).toFloat()
            }
            if (nj.has("mesh")) {
                val (gpu, names) = meshAt(nj.getInt("mesh"))
                n.mesh = gpu
                n.morphNames = names
                val mat = materialAt(meshesJson.getJSONObject(nj.getInt("mesh"))
                    .getJSONArray("primitives").getJSONObject(0).getInt("material"))
                n.color = mat.color; n.alpha = mat.alpha; n.metallic = mat.metallic
                n.rough = mat.rough; n.emissive = mat.emissive; n.transparent = mat.transparent
            }
            nj.optJSONArray("children")?.let { ch ->
                for (i in 0 until ch.length()) n.children.add(buildNode(ch.getInt(i)))
            }
            if (n.name.isNotEmpty()) byName[n.name] = n
            return n
        }

        val sceneIdx = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
            .getJSONArray("nodes").getInt(0)
        val root = buildNode(sceneIdx)
        return LoadedScene(root, byName)
    }

    private data class MaterialSpec(
        val color: FloatArray, val alpha: Float, val metallic: Float,
        val rough: Float, val emissive: FloatArray, val transparent: Boolean)

    private fun quatToMat(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val m = FloatArray(16)
        android.opengl.Matrix.setIdentityM(m, 0)
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y + z * w); m[2] = 2 * (x * z - y * w)
        m[4] = 2 * (x * y - z * w); m[5] = 1 - 2 * (x * x + z * z); m[6] = 2 * (y * z + x * w)
        m[8] = 2 * (x * z + y * w); m[9] = 2 * (y * z - x * w); m[10] = 1 - 2 * (x * x + y * y)
        return m
    }
}
