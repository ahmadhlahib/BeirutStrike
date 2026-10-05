package com.example.beirutrun.city

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A car model read from a glTF binary (.glb): its body, and its wheels apart so they can spin and
 * steer. Everything is in metres in the car's own space: +z forward, +x to the car's left, y up,
 * the ground at y = 0 and the car centred on the origin. Vertices are triangles of position (3),
 * normal (3) and texture coordinate (2), as [Mesh] takes them.
 */
class CarModel(
    val name: String,
    val body: FloatArray,
    val wheels: List<Wheel>,
    /** The colour texture (PNG bytes), or null when the model has none. */
    val image: ByteArray?,
    val halfLength: Float,
    val halfWidth: Float,
) {
    /**
     * A wheel turning on an axle along x through ([x], [y], [z]); [vertices] are round that
     * point, so it spins by turning about x and steers (front wheels) by turning about y.
     */
    class Wheel(val x: Float, val y: Float, val z: Float, val radius: Float, val front: Boolean, val vertices: FloatArray)

    /** Distance between the front and back axles (for how far the front wheels steer into a turn). */
    val wheelbase: Float get() {
        val front = wheels.filter { it.front }.map { it.z }.average()
        val back = wheels.filter { !it.front }.map { it.z }.average()
        return if (front.isNaN() || back.isNaN()) halfLength else (front - back).toFloat()
    }

    /** The wheels' radius (they all roll at the car's speed). */
    val wheelRadius: Float get() = wheels.maxOfOrNull { it.radius } ?: 0.33f
}

/**
 * Reads car models such as Kenney's Car Kit (kenney.nl, CC0): nodes named "wheel…" and a side are wheels
 * ("…front…" ones steer), everything else is the body. Low-poly kits are toy-proportioned (short
 * and tall), so the body is stretched to a real car's shape by [SCALE_X], [SCALE_Y] and [SCALE_Z],
 * and the wheels kept round, sitting on the ground in the stretched arches.
 */
object CarModels {
    /** Where the cars are, in the app's assets: every .glb in this folder, with its textures beside it. */
    const val DIR = "models/cars"

    /** Across, up and along: a Kenney sedan (1.5 × 1.3 × 2.55) becomes about 1.8 × 1.65 × 4.2 m. */
    const val SCALE_X = 1.2f
    const val SCALE_Y = 1.27f
    const val SCALE_Z = 1.65f

    /** How often each car is seen, against the others: mostly saloons and service taxis. */
    private val WEIGHTS = mapOf(
        "sedan" to 4, "taxi" to 3, "hatchback-sports" to 2, "suv" to 2,
        "suv-luxury" to 1, "sedan-sports" to 1, "van" to 1, "delivery" to 1,
    )

    fun weight(name: String) = WEIGHTS[name] ?: 1

    /** Every car in [files] (names in [DIR]) that reads; [open] gives a file's bytes from a path in [DIR]. */
    fun loadAll(files: List<String>, open: (String) -> ByteArray?): List<CarModel> =
        files.mapNotNull { file ->
            val bytes = open(file) ?: return@mapNotNull null
            runCatching { load(file.removeSuffix(".glb"), bytes, open) }.getOrNull()
        }

    fun load(name: String, glb: ByteArray, open: (String) -> ByteArray?): CarModel {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "Not a .glb file" }
        val jsonLength = bb.getInt(12)
        val json = JSONObject(String(glb, 20, jsonLength, Charsets.UTF_8))
        val binStart = 20 + jsonLength + 8
        val bin = ByteBuffer.wrap(glb, binStart, glb.size - binStart).slice().order(ByteOrder.LITTLE_ENDIAN)
        return Reader(json, bin).read(name, open)
    }

    /** One mesh placed by its node: positions and normals already in model space (as exported). */
    private class Placed(val name: String, val pos: FloatArray, val normal: FloatArray, val uv: FloatArray, val origin: FloatArray)

    private class Reader(val json: JSONObject, val bin: ByteBuffer) {
        val accessors: JSONArray = json.getJSONArray("accessors")
        val views: JSONArray = json.getJSONArray("bufferViews")

        fun read(name: String, open: (String) -> ByteArray?): CarModel {
            val nodes = json.getJSONArray("nodes")
            val parents = IntArray(nodes.length()) { -1 }
            for (i in 0 until nodes.length()) {
                val children = nodes.getJSONObject(i).optJSONArray("children") ?: continue
                for (c in 0 until children.length()) parents[children.getInt(c)] = i
            }
            // Each node's matrix in model space, parents first.
            val global = arrayOfNulls<FloatArray>(nodes.length())
            fun matrix(i: Int): FloatArray = global[i] ?: run {
                val n = nodes.getJSONObject(i)
                val local = FloatArray(16)
                val m = n.optJSONArray("matrix")
                if (m != null) for (k in 0 until 16) local[k] = m.getDouble(k).toFloat()
                else Mat.fromTrs(
                    local, n.optJSONArray("translation")?.floats() ?: floatArrayOf(0f, 0f, 0f), 0,
                    n.optJSONArray("rotation")?.floats() ?: floatArrayOf(0f, 0f, 0f, 1f), 0,
                    n.optJSONArray("scale")?.floats() ?: floatArrayOf(1f, 1f, 1f), 0,
                )
                val out = if (parents[i] < 0) local else FloatArray(16).also { Mat.multiply(it, 0, matrix(parents[i]), 0, local, 0) }
                global[i] = out
                out
            }

            val placed = ArrayList<Placed>()
            for (i in 0 until nodes.length()) {
                val n = nodes.getJSONObject(i)
                if (!n.has("mesh")) continue
                val m = matrix(i)
                val prims = json.getJSONArray("meshes").getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                for (p in 0 until prims.length()) {
                    val prim = prims.getJSONObject(p)
                    if (prim.optInt("mode", 4) != 4) continue // triangles only
                    val attrs = prim.getJSONObject("attributes")
                    val pos = readFloats(attrs.getInt("POSITION"))
                    val nor = if (attrs.has("NORMAL")) readFloats(attrs.getInt("NORMAL")) else FloatArray(pos.size)
                    val uv = if (attrs.has("TEXCOORD_0")) readFloats(attrs.getInt("TEXCOORD_0")) else FloatArray(pos.size / 3 * 2)
                    val idx = if (prim.has("indices")) readInts(prim.getInt("indices")) else IntArray(pos.size / 3) { it }
                    // Expanded to triangles, placed in model space.
                    val outPos = FloatArray(idx.size * 3); val outNor = FloatArray(idx.size * 3); val outUv = FloatArray(idx.size * 2)
                    val q = FloatArray(3)
                    for ((k, v) in idx.withIndex()) {
                        Mat.transformPoint(m, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2], q)
                        q.copyInto(outPos, k * 3)
                        val nx = nor[v * 3]; val ny = nor[v * 3 + 1]; val nz = nor[v * 3 + 2]
                        outNor[k * 3] = m[0] * nx + m[4] * ny + m[8] * nz
                        outNor[k * 3 + 1] = m[1] * nx + m[5] * ny + m[9] * nz
                        outNor[k * 3 + 2] = m[2] * nx + m[6] * ny + m[10] * nz
                        outUv[k * 2] = uv[v * 2]; outUv[k * 2 + 1] = uv[v * 2 + 1]
                    }
                    placed += Placed(n.optString("name", "node$i"), outPos, outNor, outUv, floatArrayOf(m[12], m[13], m[14]))
                }
            }
            return build(name, placed, image(open))
        }

        /** The base colour texture's bytes: embedded in the .glb, or a file next to it. */
        private fun image(open: (String) -> ByteArray?): ByteArray? {
            val images = json.optJSONArray("images") ?: return null
            if (images.length() == 0) return null
            val img = images.getJSONObject(0)
            if (img.has("bufferView")) {
                val view = views.getJSONObject(img.getInt("bufferView"))
                val start = view.optInt("byteOffset", 0)
                return ByteArray(view.getInt("byteLength")) { k -> bin.get(start + k) }
            }
            val uri = img.optString("uri").takeIf { it.isNotEmpty() && !it.startsWith("data:") } ?: return null
            return open(uri)
        }

        private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }

        private fun layout(index: Int): IntArray {
            val a = accessors.getJSONObject(index)
            val view = views.getJSONObject(a.getInt("bufferView"))
            val components = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 16 }
            val type = a.getInt("componentType")
            val size = when (type) { FLOAT, UNSIGNED_INT -> 4; UNSIGNED_SHORT, SHORT -> 2; else -> 1 }
            val stride = view.optInt("byteStride", 0).takeIf { it > 0 } ?: (components * size)
            val start = view.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
            return intArrayOf(start, a.getInt("count"), components, type, stride)
        }

        fun readFloats(index: Int): FloatArray {
            val (start, count, components, type, stride) = layout(index)
            val normalized = accessors.getJSONObject(index).optBoolean("normalized", false)
            val out = FloatArray(count * components)
            for (i in 0 until count) for (c in 0 until components) {
                out[i * components + c] = when (type) {
                    FLOAT -> bin.getFloat(start + i * stride + c * 4)
                    UNSIGNED_SHORT -> (bin.getShort(start + i * stride + c * 2).toInt() and 0xFFFF) / if (normalized) 65535f else 1f
                    UNSIGNED_BYTE -> (bin.get(start + i * stride + c).toInt() and 0xFF) / if (normalized) 255f else 1f
                    SHORT -> bin.getShort(start + i * stride + c * 2).let { if (normalized) max(it / 32767f, -1f) else it.toFloat() }
                    BYTE -> bin.get(start + i * stride + c).let { if (normalized) max(it / 127f, -1f) else it.toFloat() }
                    else -> error("Unsupported component type $type")
                }
            }
            return out
        }

        fun readInts(index: Int): IntArray {
            val (start, count, _, type, stride) = layout(index)
            return IntArray(count) { i ->
                when (type) {
                    UNSIGNED_BYTE -> bin.get(start + i * stride).toInt() and 0xFF
                    UNSIGNED_SHORT -> bin.getShort(start + i * stride).toInt() and 0xFFFF
                    UNSIGNED_INT -> bin.getInt(start + i * stride)
                    else -> error("Unsupported index type $type")
                }
            }
        }
    }

    /**
     * Stretches the body to a real car's proportions and centres it; each wheel keeps its round
     * shape (scaled by the mean of the up and along stretch), rests on the ground, and the body
     * is lowered or raised so its arches stay centred on the wheels.
     */
    private fun build(name: String, placed: List<Placed>, image: ByteArray?): CarModel {
        val wheelScale = sqrt(SCALE_Y * SCALE_Z)
        // Wheels on the road name their side ("wheel-front-left"); one that doesn't, such as an
        // SUV's spare on the back door ("wheel-back"), is part of the body.
        val named = placed.filter { it.name.startsWith("wheel", ignoreCase = true) }
        val sided = named.filter { it.name.contains("left", ignoreCase = true) || it.name.contains("right", ignoreCase = true) }
        val wheelParts = sided.ifEmpty { named }
        val bodyParts = placed - wheelParts.toSet()
        require(bodyParts.isNotEmpty()) { "$name has no body" }

        // Centre the body across and along.
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (p in bodyParts) for (k in 0 until p.pos.size / 3) {
            x0 = min(x0, p.pos[k * 3]); x1 = max(x1, p.pos[k * 3])
            z0 = min(z0, p.pos[k * 3 + 2]); z1 = max(z1, p.pos[k * 3 + 2])
        }
        val cx = (x0 + x1) / 2f; val cz = (z0 + z1) / 2f

        // Wheels: round their own axle, in model space as exported.
        class Raw(val part: Placed, val radius: Float)
        val raw = wheelParts.map { w ->
            var r = 0f
            for (k in 0 until w.pos.size / 3) {
                val dy = w.pos[k * 3 + 1] - w.origin[1]; val dz = w.pos[k * 3 + 2] - w.origin[2]
                r = max(r, sqrt(dy * dy + dz * dz))
            }
            Raw(w, r)
        }
        // How far up the body must move so the arch over the wheel (at the exported axle
        // height × SCALE_Y) sits on the wheel's centre (its radius × wheelScale off the ground).
        val axleY = raw.map { it.part.origin[1] }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val radius = raw.map { it.radius }.average().toFloat().takeIf { !it.isNaN() } ?: 0f
        val lift = radius * wheelScale - axleY * SCALE_Y
        var ground = Float.MAX_VALUE
        for (p in bodyParts) for (k in 0 until p.pos.size / 3) ground = min(ground, p.pos[k * 3 + 1] * SCALE_Y + lift)
        // With no wheels, it stands on the ground.
        val bodyLift = if (raw.isEmpty()) -ground + lift else lift

        val body = pack(bodyParts) { x, y, z, out ->
            out[0] = (x - cx) * SCALE_X; out[1] = y * SCALE_Y + bodyLift; out[2] = (z - cz) * SCALE_Z
        }
        val bodyNormalScale = floatArrayOf(1f / SCALE_X, 1f / SCALE_Y, 1f / SCALE_Z)
        scaleNormals(body, bodyNormalScale)

        val wheels = raw.map { w ->
            val o = w.part.origin
            val r = w.radius * wheelScale
            val v = pack(listOf(w.part)) { x, y, z, out ->
                out[0] = (x - o[0]) * SCALE_X; out[1] = (y - o[1]) * wheelScale; out[2] = (z - o[2]) * wheelScale
            }
            scaleNormals(v, floatArrayOf(1f / SCALE_X, 1f / wheelScale, 1f / wheelScale))
            val z = (o[2] - cz) * SCALE_Z
            val front = when {
                w.part.name.contains("front", ignoreCase = true) -> true
                w.part.name.contains("back", ignoreCase = true) || w.part.name.contains("rear", ignoreCase = true) -> false
                else -> z > 0f
            }
            CarModel.Wheel((o[0] - cx) * SCALE_X, r, z, r, front, v)
        }
        val halfLength = (z1 - z0) / 2f * SCALE_Z
        val halfWidth = max(abs(x0 - cx), abs(x1 - cx)) * SCALE_X
        return CarModel(name, body, wheels, image, halfLength, halfWidth)
    }

    /** Parts as [Mesh] vertices, each position mapped by [place]. */
    private inline fun pack(parts: List<Placed>, place: (Float, Float, Float, FloatArray) -> Unit): FloatArray {
        val out = FloatArray(parts.sumOf { it.pos.size / 3 } * Mesh.FLOATS)
        val q = FloatArray(3)
        var o = 0
        for (p in parts) for (k in 0 until p.pos.size / 3) {
            place(p.pos[k * 3], p.pos[k * 3 + 1], p.pos[k * 3 + 2], q)
            out[o] = q[0]; out[o + 1] = q[1]; out[o + 2] = q[2]
            out[o + 3] = p.normal[k * 3]; out[o + 4] = p.normal[k * 3 + 1]; out[o + 5] = p.normal[k * 3 + 2]
            out[o + 6] = p.uv[k * 2]; out[o + 7] = p.uv[k * 2 + 1]
            o += Mesh.FLOATS
        }
        return out
    }

    /** A stretch by s turns normals by 1 / s (then they're made unit length again). */
    private fun scaleNormals(v: FloatArray, inv: FloatArray) {
        var o = 0
        while (o < v.size) {
            val nx = v[o + 3] * inv[0]; val ny = v[o + 4] * inv[1]; val nz = v[o + 5] * inv[2]
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-6f) { v[o + 3] = nx / l; v[o + 4] = ny / l; v[o + 5] = nz / l }
            o += Mesh.FLOATS
        }
    }

    private const val FLOAT = 5126
    private const val BYTE = 5120
    private const val UNSIGNED_BYTE = 5121
    private const val SHORT = 5122
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
}
