package com.example.beirutrun.city

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rigged, animated character loaded from a glTF binary (.glb): meshes with per-vertex bone
 * weights, the skeleton, and animation clips. Plain Kotlin (plus org.json), so posing and
 * skinning can run off the GL thread and be unit tested.
 *
 * Posing works on "local" transforms (translation, rotation quaternion, scale per node);
 * [SkinnedPose] samples clips into those, blends them, and turns them into skinned vertices.
 */
class SkinnedModel private constructor(
    val nodes: List<Node>,
    val skins: List<Skin>,
    val primitives: List<Primitive>,
    val clips: Map<String, Clip>,
    /** Embedded texture images (PNG/JPEG bytes), referenced by [Primitive.image]. */
    val images: List<ByteArray>,
) {
    class Node(
        val name: String,
        val parent: Int,
        val t: FloatArray, val r: FloatArray, val s: FloatArray,
    )

    class Skin(val joints: IntArray, val inverseBind: FloatArray)

    /**
     * One mesh part with a single material. [joints] holds 4 skin-joint slots per vertex and
     * [weights] the matching 4 weights.
     */
    class Primitive(
        val material: String,
        val skin: Int,
        val positions: FloatArray,
        val normals: FloatArray,
        val joints: IntArray,
        val weights: FloatArray,
        val indices: ShortArray,
        /** Texture coordinates (2 per vertex), or null when the material has no texture. */
        val uvs: FloatArray? = null,
        /** Index into [SkinnedModel.images] of the colour texture, or -1. */
        val image: Int = -1,
        /** The material's own colour (ARGB, sRGB), used when it isn't recoloured for a team. */
        val baseColor: Int = 0xFFFFFFFF.toInt(),
    ) {
        val vertexCount get() = positions.size / 3
    }

    /** A channel animates one property ([path]: 0 translation, 1 rotation, 2 scale) of one node. */
    class Channel(val node: Int, val path: Int, val times: FloatArray, val values: FloatArray, val step: Boolean)

    /**
     * An animation. [rootSpeed] is how fast the clip originally walked the body forward (m/s in
     * model units per second), measured before that travel was removed; 0 for in-place clips.
     */
    class Clip(val name: String, val duration: Float, val channels: List<Channel>, val rootSpeed: Float = 0f)

    /** Nodes ordered so every parent comes before its children. */
    val order: IntArray = run {
        val out = ArrayList<Int>(nodes.size)
        val done = BooleanArray(nodes.size)
        fun visit(i: Int) {
            if (done[i]) return
            nodes[i].parent.takeIf { it >= 0 }?.let(::visit)
            done[i] = true
            out += i
        }
        nodes.indices.forEach(::visit)
        out.toIntArray()
    }

    fun nodeIndex(name: String) = nodes.indexOfFirst { it.name == name }

    /** The clip whose name ends with [suffix] (Blender exports them as "Armature|Run"). */
    fun clip(suffix: String): Clip? = clips[suffix] ?: clips.values.firstOrNull { it.name.endsWith("|$suffix") }

    /**
     * A copy with more clips: the first clip of each animation-only model in [extra], stored under
     * the given name. Bones are matched by name (Mixamo animations come one per file, all on the
     * same "mixamorig:" skeleton); channels for bones this model doesn't have are dropped.
     *
     * Clips that walk the body forward ("In Place" not ticked when downloading) have that travel
     * removed from [rootBone], keeping only its up-and-down bounce: the game moves the player
     * itself, and otherwise the body would run ahead and snap back every loop. How fast the clip
     * travelled is kept as [Clip.rootSpeed] so playback can match the player's real speed.
     */
    fun withClips(extra: Map<String, SkinnedModel>, rootBone: String? = null): SkinnedModel {
        val byName = nodes.withIndex().associate { (i, n) -> n.name to i }
        val root = rootBone?.let { byName[it] } ?: -1
        val merged = HashMap(clips)
        for ((name, source) in extra) {
            val clip = source.clips.values.firstOrNull() ?: continue
            var rootSpeed = 0f
            val channels = clip.channels.mapNotNull { ch ->
                val target = byName[source.nodes[ch.node].name] ?: return@mapNotNull null
                var values = ch.values
                if (target == root && ch.path == 0 && ch.times.size > 1) {
                    val n = ch.times.size
                    val dx = values[(n - 1) * 3] - values[0]
                    val dz = values[(n - 1) * 3 + 2] - values[2]
                    if (clip.duration > 0f) rootSpeed = sqrt(dx * dx + dz * dz) / clip.duration
                    // Pin x and z to the first frame; keep y (the bounce of each step).
                    values = values.copyOf()
                    for (k in 0 until n) { values[k * 3] = values[0]; values[k * 3 + 2] = values[2] }
                }
                SkinnedModel.Channel(target, ch.path, ch.times, values, ch.step)
            }
            merged[name] = Clip(name, clip.duration, channels, rootSpeed)
        }
        return SkinnedModel(nodes, skins, primitives, merged, images)
    }

    companion object {
        private const val FLOAT = 5126
        private const val BYTE = 5120
        private const val UNSIGNED_BYTE = 5121
        private const val SHORT = 5122
        private const val UNSIGNED_SHORT = 5123
        private const val UNSIGNED_INT = 5125

        fun load(glb: ByteArray): SkinnedModel {
            val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.getInt(0) == 0x46546C67) { "Not a .glb file" }
            val jsonLength = bb.getInt(12)
            val json = JSONObject(String(glb, 20, jsonLength, Charsets.UTF_8))
            val binStart = 20 + jsonLength + 8
            val bin = ByteBuffer.wrap(glb, binStart, glb.size - binStart).slice().order(ByteOrder.LITTLE_ENDIAN)
            return Parser(json, bin).parse()
        }
    }

    private class Parser(val json: JSONObject, val bin: ByteBuffer) {
        val accessors: JSONArray = json.getJSONArray("accessors")
        val views: JSONArray = json.getJSONArray("bufferViews")

        fun parse(): SkinnedModel {
            val nodesJson = json.getJSONArray("nodes")
            val parents = IntArray(nodesJson.length()) { -1 }
            for (i in 0 until nodesJson.length()) {
                val children = nodesJson.getJSONObject(i).optJSONArray("children") ?: continue
                for (c in 0 until children.length()) parents[children.getInt(c)] = i
            }
            val nodes = List(nodesJson.length()) { i ->
                val n = nodesJson.getJSONObject(i)
                Node(
                    n.optString("name", "node$i"), parents[i],
                    n.optJSONArray("translation")?.floats() ?: floatArrayOf(0f, 0f, 0f),
                    n.optJSONArray("rotation")?.floats() ?: floatArrayOf(0f, 0f, 0f, 1f),
                    n.optJSONArray("scale")?.floats() ?: floatArrayOf(1f, 1f, 1f),
                )
            }

            val skinsJson = json.optJSONArray("skins") ?: JSONArray()
            val skins = List(skinsJson.length()) { i ->
                val s = skinsJson.getJSONObject(i)
                val joints = s.getJSONArray("joints").let { a -> IntArray(a.length()) { a.getInt(it) } }
                Skin(joints, readFloats(s.getInt("inverseBindMatrices")))
            }

            val materials = json.optJSONArray("materials")
            val textures = json.optJSONArray("textures")
            val primitives = ArrayList<Primitive>()
            for (i in 0 until nodesJson.length()) {
                val n = nodesJson.getJSONObject(i)
                if (!n.has("mesh") || !n.has("skin")) continue
                val mesh = json.getJSONArray("meshes").getJSONObject(n.getInt("mesh"))
                val prims = mesh.getJSONArray("primitives")
                for (p in 0 until prims.length()) {
                    val prim = prims.getJSONObject(p)
                    val attrs = prim.getJSONObject("attributes")
                    val mat = prim.optInt("material", -1).takeIf { it >= 0 }?.let { materials?.getJSONObject(it) }
                    val pbr = mat?.optJSONObject("pbrMetallicRoughness")
                    val texture = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1) ?: -1
                    // A WebP texture (as gltfpack writes them) names its image in an extension.
                    val image = if (texture >= 0) textures?.optJSONObject(texture)?.let { t ->
                        t.optInt("source", -1).takeIf { it >= 0 }
                            ?: t.optJSONObject("extensions")?.optJSONObject("EXT_texture_webp")?.optInt("source", -1)
                    } ?: -1 else -1
                    primitives += Primitive(
                        mat?.optString("name").orEmpty(), n.getInt("skin"),
                        readFloats(attrs.getInt("POSITION")),
                        readFloats(attrs.getInt("NORMAL")),
                        readInts(attrs.getInt("JOINTS_0")),
                        readFloats(attrs.getInt("WEIGHTS_0")),
                        readInts(prim.getInt("indices")).let { idx -> ShortArray(idx.size) { idx[it].toShort() } },
                        uvs = if (image >= 0 && attrs.has("TEXCOORD_0")) readFloats(attrs.getInt("TEXCOORD_0")) else null,
                        image = image,
                        baseColor = pbr?.optJSONArray("baseColorFactor")?.let(::srgbColor) ?: 0xFFFFFFFF.toInt(),
                    )
                }
            }

            val clips = HashMap<String, Clip>()
            val anims = json.optJSONArray("animations") ?: JSONArray()
            for (a in 0 until anims.length()) {
                val anim = anims.getJSONObject(a)
                val samplers = anim.getJSONArray("samplers")
                val channels = ArrayList<Channel>()
                var duration = 0f
                val channelsJson = anim.getJSONArray("channels")
                for (c in 0 until channelsJson.length()) {
                    val ch = channelsJson.getJSONObject(c)
                    val target = ch.getJSONObject("target")
                    if (!target.has("node")) continue
                    val path = when (target.getString("path")) {
                        "translation" -> 0
                        "rotation" -> 1
                        "scale" -> 2
                        else -> continue // morph weights: not used
                    }
                    val sampler = samplers.getJSONObject(ch.getInt("sampler"))
                    val times = readFloats(sampler.getInt("input"))
                    var values = readFloats(sampler.getInt("output"))
                    val interpolation = sampler.optString("interpolation", "LINEAR")
                    val width = if (path == 1) 4 else 3
                    if (interpolation == "CUBICSPLINE") {
                        // Keep just the values (drop the in/out tangents); good enough for games.
                        values = FloatArray(times.size * width) { k -> values[(k / width) * width * 3 + width + k % width] }
                    }
                    duration = max(duration, times.lastOrNull() ?: 0f)
                    channels += Channel(target.getInt("node"), path, times, values, interpolation == "STEP")
                }
                val name = anim.optString("name", "clip$a")
                clips[name] = Clip(name, duration, channels)
            }
            val imagesJson = json.optJSONArray("images") ?: JSONArray()
            val images = List(imagesJson.length()) { i ->
                val img = imagesJson.getJSONObject(i)
                if (!img.has("bufferView")) return@List ByteArray(0) // external files aren't supported
                val view = views.getJSONObject(img.getInt("bufferView"))
                val start = view.optInt("byteOffset", 0)
                ByteArray(view.getInt("byteLength")) { k -> bin.get(start + k) }
            }
            return SkinnedModel(nodes, skins, primitives, clips, images)
        }

        /** glTF colours are linear; the game's shader works in sRGB. */
        private fun srgbColor(c: JSONArray): Int {
            fun ch(i: Int) = (Math.pow(c.optDouble(i, 1.0).coerceIn(0.0, 1.0), 1.0 / 2.2) * 255).toInt()
            return (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }

        private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }

        private fun componentsOf(type: String) = when (type) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16
            else -> error("Unsupported accessor type $type")
        }

        /** Start offset, element count, components, component type and stride of an accessor. */
        private fun layout(index: Int): IntArray {
            val a = accessors.getJSONObject(index)
            val view = views.getJSONObject(a.getInt("bufferView"))
            val components = componentsOf(a.getString("type"))
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
                    // Signed, as gltfpack stores animated rotations.
                    SHORT -> bin.getShort(start + i * stride + c * 2).let { if (normalized) max(it / 32767f, -1f) else it.toFloat() }
                    BYTE -> bin.get(start + i * stride + c).let { if (normalized) max(it / 127f, -1f) else it.toFloat() }
                    else -> error("Unsupported float component type $type")
                }
            }
            return out
        }

        fun readInts(index: Int): IntArray {
            val (start, count, components, type, stride) = layout(index)
            val out = IntArray(count * components)
            for (i in 0 until count) for (c in 0 until components) {
                out[i * components + c] = when (type) {
                    UNSIGNED_BYTE -> bin.get(start + i * stride + c).toInt() and 0xFF
                    UNSIGNED_SHORT -> bin.getShort(start + i * stride + c * 2).toInt() and 0xFFFF
                    UNSIGNED_INT -> bin.getInt(start + i * stride + c * 4)
                    else -> error("Unsupported index component type $type")
                }
            }
            return out
        }

    }
}

/**
 * One character's current pose: samples up to two clips (the current one and the one it is fading
 * from), blends them, and produces bone matrices, skinned vertices and attachment points.
 */
class SkinnedPose(val model: SkinnedModel) {
    private val n = model.nodes.size
    private val t = FloatArray(n * 3)
    private val r = FloatArray(n * 4)
    private val s = FloatArray(n * 3)
    private val t2 = FloatArray(n * 3)
    private val r2 = FloatArray(n * 4)
    private val s2 = FloatArray(n * 3)

    /** Model-space matrix of every node after [update] (column-major 4x4, 16 floats each). */
    val global = FloatArray(n * 16)
    private val jointMatrices = model.skins.map { FloatArray(it.joints.size * 16) }

    /** Skinned vertex data per primitive: position (3), normal (3), uv (2, unused), ready for a Mesh. */
    val vertices: List<FloatArray> = model.primitives.map { p ->
        FloatArray(p.vertexCount * 8).also { v ->
            // Texture coordinates never change; fill them in once.
            p.uvs?.let { uv -> for (i in 0 until p.vertexCount) { v[i * 8 + 6] = uv[i * 2]; v[i * 8 + 7] = uv[i * 2 + 1] } }
        }
    }

    /**
     * Poses the character: [clip] at [time], blended over [fromClip] at [fromTime] by (1 - [fade]).
     * Pass [fade] = 1 when not blending.
     */
    fun update(clip: SkinnedModel.Clip?, time: Float, fromClip: SkinnedModel.Clip?, fromTime: Float, fade: Float) {
        rest(t, r, s)
        clip?.let { sample(it, time, t, r, s) }
        if (fromClip != null && fade < 1f) {
            rest(t2, r2, s2)
            sample(fromClip, fromTime, t2, r2, s2)
            // result = from blended towards current by `fade`
            for (i in 0 until n) {
                for (k in 0..2) {
                    t[i * 3 + k] = t2[i * 3 + k] + (t[i * 3 + k] - t2[i * 3 + k]) * fade
                    s[i * 3 + k] = s2[i * 3 + k] + (s[i * 3 + k] - s2[i * 3 + k]) * fade
                }
                slerp(r2, i * 4, r, i * 4, fade, r, i * 4)
            }
        }
        computeGlobals()
    }

    private fun rest(t: FloatArray, r: FloatArray, s: FloatArray) {
        for ((i, node) in model.nodes.withIndex()) {
            node.t.copyInto(t, i * 3)
            node.r.copyInto(r, i * 4)
            node.s.copyInto(s, i * 3)
        }
    }

    private fun sample(clip: SkinnedModel.Clip, time: Float, t: FloatArray, r: FloatArray, s: FloatArray) {
        for (ch in clip.channels) {
            val times = ch.times
            val w = if (ch.path == 1) 4 else 3
            val target = when (ch.path) { 0 -> t; 1 -> r; else -> s }
            val offset = ch.node * w
            if (times.size == 1 || time <= times[0]) {
                ch.values.copyInto(target, offset, 0, w)
                continue
            }
            if (time >= times.last()) {
                ch.values.copyInto(target, offset, (times.size - 1) * w, times.size * w)
                continue
            }
            // Binary search for the key pair around `time`.
            var lo = 0
            var hi = times.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= time) lo = mid else hi = mid
            }
            val k = if (ch.step) 0f else (time - times[lo]) / (times[hi] - times[lo])
            if (ch.path == 1) {
                slerp(ch.values, lo * 4, ch.values, hi * 4, k, target, offset)
            } else {
                for (c in 0..2) {
                    val a = ch.values[lo * 3 + c]
                    target[offset + c] = a + (ch.values[hi * 3 + c] - a) * k
                }
            }
        }
    }

    private fun computeGlobals() {
        val local = FloatArray(16)
        for (i in model.order) {
            Mat.fromTrs(local, t, i * 3, r, i * 4, s, i * 3)
            val parent = model.nodes[i].parent
            if (parent < 0) local.copyInto(global, i * 16)
            else Mat.multiply(global, i * 16, global, parent * 16, local, 0)
        }
    }

    /** Skins every vertex with the current bone matrices, writing into [vertices]. */
    fun skin() {
        for ((si, skin) in model.skins.withIndex()) {
            val jm = jointMatrices[si]
            for (j in skin.joints.indices) Mat.multiply(jm, j * 16, global, skin.joints[j] * 16, skin.inverseBind, j * 16)
        }
        for ((pi, prim) in model.primitives.withIndex()) {
            val jm = jointMatrices[prim.skin]
            val out = vertices[pi]
            val pos = prim.positions
            val nor = prim.normals
            val joints = prim.joints
            val weights = prim.weights
            for (v in 0 until prim.vertexCount) {
                val px = pos[v * 3]; val py = pos[v * 3 + 1]; val pz = pos[v * 3 + 2]
                val nx = nor[v * 3]; val ny = nor[v * 3 + 1]; val nz = nor[v * 3 + 2]
                var ox = 0f; var oy = 0f; var oz = 0f
                var mx = 0f; var my = 0f; var mz = 0f
                for (k in 0..3) {
                    val w = weights[v * 4 + k]
                    if (w <= 0f) continue
                    val m = joints[v * 4 + k] * 16
                    ox += w * (jm[m] * px + jm[m + 4] * py + jm[m + 8] * pz + jm[m + 12])
                    oy += w * (jm[m + 1] * px + jm[m + 5] * py + jm[m + 9] * pz + jm[m + 13])
                    oz += w * (jm[m + 2] * px + jm[m + 6] * py + jm[m + 10] * pz + jm[m + 14])
                    mx += w * (jm[m] * nx + jm[m + 4] * ny + jm[m + 8] * nz)
                    my += w * (jm[m + 1] * nx + jm[m + 5] * ny + jm[m + 9] * nz)
                    mz += w * (jm[m + 2] * nx + jm[m + 6] * ny + jm[m + 10] * nz)
                }
                val o = v * 8
                out[o] = ox; out[o + 1] = oy; out[o + 2] = oz
                out[o + 3] = mx; out[o + 4] = my; out[o + 5] = mz
            }
        }
    }

    /** The current model-space matrix of node [index] copied into [out]. */
    fun nodeMatrix(index: Int, out: FloatArray) = global.copyInto(out, 0, index * 16, index * 16 + 16)

    // ---- Posing by hand (after [update], before [skin]) ----------------------------------------

    /** Each node and everything below it, parents first. */
    private val subtrees: Array<IntArray> by lazy {
        Array(n) { root ->
            model.order.filter { i ->
                var p = i
                while (p >= 0 && p != root) p = model.nodes[p].parent
                p == root
            }.toIntArray()
        }
    }
    private val rot = FloatArray(9)
    private val snap = FloatArray(16)

    /**
     * Turns [node] (and everything below it) about its joint so that it points from its joint
     * towards [tip]'s joint along model-space direction ([dx], [dy], [dz]), by [weight] (0..1) of
     * the way. Lets a pose be made from directions, whatever the model's bone axes are.
     */
    fun aim(node: Int, tip: Int, dx: Float, dy: Float, dz: Float, weight: Float = 1f) {
        if (node < 0 || tip < 0) return
        val o = node * 16
        val px = global[o + 12]; val py = global[o + 13]; val pz = global[o + 14]
        var ax = global[tip * 16 + 12] - px; var ay = global[tip * 16 + 13] - py; var az = global[tip * 16 + 14] - pz
        var len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-6f) return
        ax /= len; ay /= len; az /= len
        len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6f) return
        val bx = dx / len; val by = dy / len; val bz = dz / len
        // Shortest turn from a to b: about a × b, by the angle between them.
        var kx = ay * bz - az * by; var ky = az * bx - ax * bz; var kz = ax * by - ay * bx
        val sinA = sqrt(kx * kx + ky * ky + kz * kz)
        val cosA = (ax * bx + ay * by + az * bz).coerceIn(-1f, 1f)
        if (sinA < 1e-6f) return
        kx /= sinA; ky /= sinA; kz /= sinA
        val angle = atan2(sinA, cosA) * weight
        rotation(kx, ky, kz, angle)
        for (j in subtrees[node]) turn(j, px, py, pz)
    }

    /** Copies node [index]'s current matrix into [out] (to [follow] it later). */
    fun snapshot(index: Int, out: FloatArray) = nodeMatrix(index, out)

    /**
     * Moves [node] (and everything below it) the way [leader] has moved since [leaderBefore] was
     * taken, as if it were attached to it (for feet that aren't children of the legs).
     */
    fun follow(node: Int, leader: Int, leaderBefore: FloatArray) {
        if (node < 0 || leader < 0) return
        // delta = leaderNow × inverse(leaderBefore). Bone matrices are rotation × uniform scale
        // (the armature may be scaled) + translation, so the inverse is transpose / scale².
        val inv = FloatArray(16)
        val b = leaderBefore
        val scale2 = (b[0] * b[0] + b[1] * b[1] + b[2] * b[2]).takeIf { it > 0f } ?: 1f
        for (c in 0..2) for (r in 0..2) inv[c * 4 + r] = b[r * 4 + c] / scale2
        inv[15] = 1f
        inv[12] = -(inv[0] * b[12] + inv[4] * b[13] + inv[8] * b[14])
        inv[13] = -(inv[1] * b[12] + inv[5] * b[13] + inv[9] * b[14])
        inv[14] = -(inv[2] * b[12] + inv[6] * b[13] + inv[10] * b[14])
        val delta = FloatArray(16)
        Mat.multiply(delta, 0, global, leader * 16, inv, 0)
        for (j in subtrees[node]) {
            global.copyInto(snap, 0, j * 16, j * 16 + 16)
            Mat.multiply(global, j * 16, delta, 0, snap, 0)
        }
    }

    /** [rot] = rotation by [angle] about unit axis (x, y, z), column-major 3x3. */
    private fun rotation(x: Float, y: Float, z: Float, angle: Float) {
        val c = cos(angle); val s = sin(angle); val t = 1f - c
        rot[0] = t * x * x + c;     rot[1] = t * x * y + s * z; rot[2] = t * x * z - s * y
        rot[3] = t * x * y - s * z; rot[4] = t * y * y + c;     rot[5] = t * y * z + s * x
        rot[6] = t * x * z + s * y; rot[7] = t * y * z - s * x; rot[8] = t * z * z + c
    }

    /** Applies [rot] about the point (px, py, pz) to node [j]'s matrix. */
    private fun turn(j: Int, px: Float, py: Float, pz: Float) {
        val o = j * 16
        for (col in 0..2) {
            val x = global[o + col * 4]; val y = global[o + col * 4 + 1]; val z = global[o + col * 4 + 2]
            global[o + col * 4] = rot[0] * x + rot[3] * y + rot[6] * z
            global[o + col * 4 + 1] = rot[1] * x + rot[4] * y + rot[7] * z
            global[o + col * 4 + 2] = rot[2] * x + rot[5] * y + rot[8] * z
        }
        val x = global[o + 12] - px; val y = global[o + 13] - py; val z = global[o + 14] - pz
        global[o + 12] = px + rot[0] * x + rot[3] * y + rot[6] * z
        global[o + 13] = py + rot[1] * x + rot[4] * y + rot[7] * z
        global[o + 14] = pz + rot[2] * x + rot[5] * y + rot[8] * z
    }

    companion object {
        /** Spherical interpolation of quaternions a → b by k, taking the short way round. */
        fun slerp(a: FloatArray, ao: Int, b: FloatArray, bo: Int, k: Float, out: FloatArray, oo: Int) {
            var bx = b[bo]; var by = b[bo + 1]; var bz = b[bo + 2]; var bw = b[bo + 3]
            val ax = a[ao]; val ay = a[ao + 1]; val az = a[ao + 2]; val aw = a[ao + 3]
            var dot = ax * bx + ay * by + az * bz + aw * bw
            if (dot < 0f) { dot = -dot; bx = -bx; by = -by; bz = -bz; bw = -bw }
            val ka: Float
            val kb: Float
            if (dot > 0.9995f) {
                ka = 1f - k; kb = k
            } else {
                val theta = acos(dot.coerceIn(-1f, 1f))
                val sinTheta = sin(theta)
                ka = sin((1f - k) * theta) / sinTheta
                kb = sin(k * theta) / sinTheta
            }
            var x = ax * ka + bx * kb; var y = ay * ka + by * kb; var z = az * ka + bz * kb; var w = aw * ka + bw * kb
            val len = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-8f } ?: 1f
            x /= len; y /= len; z /= len; w /= len
            out[oo] = x; out[oo + 1] = y; out[oo + 2] = z; out[oo + 3] = w
        }
    }
}

/** Small column-major 4x4 matrix helpers (Android's Matrix isn't available in unit tests). */
object Mat {
    fun identity(out: FloatArray, o: Int = 0) {
        for (i in 0 until 16) out[o + i] = if (i % 5 == 0) 1f else 0f
    }

    /** out = a × b. [out] may not overlap [b]. */
    fun multiply(out: FloatArray, oo: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        for (col in 0..3) {
            val b0 = b[bo + col * 4]; val b1 = b[bo + col * 4 + 1]; val b2 = b[bo + col * 4 + 2]; val b3 = b[bo + col * 4 + 3]
            for (row in 0..3) {
                out[oo + col * 4 + row] = a[ao + row] * b0 + a[ao + 4 + row] * b1 + a[ao + 8 + row] * b2 + a[ao + 12 + row] * b3
            }
        }
    }

    /** Translation × rotation (quaternion x, y, z, w) × scale. */
    fun fromTrs(out: FloatArray, t: FloatArray, to: Int, q: FloatArray, qo: Int, s: FloatArray, so: Int) {
        val x = q[qo]; val y = q[qo + 1]; val z = q[qo + 2]; val w = q[qo + 3]
        val sx = s[so]; val sy = s[so + 1]; val sz = s[so + 2]
        out[0] = (1 - 2 * (y * y + z * z)) * sx
        out[1] = (2 * (x * y + z * w)) * sx
        out[2] = (2 * (x * z - y * w)) * sx
        out[3] = 0f
        out[4] = (2 * (x * y - z * w)) * sy
        out[5] = (1 - 2 * (x * x + z * z)) * sy
        out[6] = (2 * (y * z + x * w)) * sy
        out[7] = 0f
        out[8] = (2 * (x * z + y * w)) * sz
        out[9] = (2 * (y * z - x * w)) * sz
        out[10] = (1 - 2 * (x * x + y * y)) * sz
        out[11] = 0f
        out[12] = t[to]; out[13] = t[to + 1]; out[14] = t[to + 2]; out[15] = 1f
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
    }
}
