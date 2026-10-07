package com.example.beirutrun.city

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rock rising out of the sea (Pigeon Rocks off Raouche), shaped like the real limestone stacks
 * rather than a building: rough cliffs following its footprint, a wave-cut notch at the
 * waterline, ledges where the rock's layers stand out, a top narrower than its foot and rounded
 * off, scrub on top, and for the big rock a sea arch right through it.
 *
 * The rock is described as a solid (a signed distance: negative inside) and its surface found by
 * marching tetrahedra over a grid, with smooth normals from the solid itself. Plain Kotlin, so it
 * can be built on a background thread and unit tested.
 */
class SeaStack(
    /** The footprint where it meets the sea (x, z pairs). */
    private val footprint: FloatArray,
    /** How high it rises above the sea, metres. */
    private val height: Float,
    /** A sea arch through it (the big Pigeon Rock), looking towards ([viewX], [viewZ]) on the shore. */
    private val arch: Boolean,
    private val viewX: Float = 0f,
    private val viewZ: Float = 0f,
    seed: Int,
) {
    /** A finished triangle: three corners (x, y, z), their normals, and what it's made of. */
    class Triangle(val p: FloatArray, val n: FloatArray, val part: Part)

    enum class Part { CLIFF, WET, SCRUB }

    private val cx: Float
    private val cz: Float
    private val radius: Float
    private val noiseSeed = seed * 7919 + 13
    /** The arch runs along this direction (unit), through the middle. */
    private val archDx: Float
    private val archDz: Float

    init {
        var sx = 0f; var sz = 0f
        val n = footprint.size / 2
        for (i in 0 until n) { sx += footprint[2 * i]; sz += footprint[2 * i + 1] }
        cx = sx / n; cz = sz / n
        radius = sqrt(abs(CityMap.signedArea(footprint)) / Math.PI.toFloat())
        // The arch looks towards the shore (where people see it from), so the sea shows through it.
        val tx = viewX - cx; val tz = viewZ - cz
        val tl = sqrt(tx * tx + tz * tz).takeIf { it > 1f } ?: 1f
        archDx = tx / tl; archDz = tz / tl
    }

    /** The rock as triangles in world space. */
    fun triangles(): List<Triangle> {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until footprint.size / 2) {
            minX = min(minX, footprint[2 * i]); maxX = max(maxX, footprint[2 * i])
            minZ = min(minZ, footprint[2 * i + 1]); maxZ = max(maxZ, footprint[2 * i + 1])
        }
        val pad = 4f
        val x0 = minX - pad; val z0 = minZ - pad; val y0 = BOTTOM
        val nx = ((maxX + pad - x0) / STEP).toInt() + 1
        val nz = ((maxZ + pad - z0) / STEP).toInt() + 1
        val ny = ((height + 3f - y0) / STEP).toInt() + 1
        val v = FloatArray(nx * ny * nz)
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            v[(k * ny + j) * nx + i] = distance(x0 + i * STEP, y0 + j * STEP, z0 + k * STEP)
        }
        val out = ArrayList<Triangle>()
        val cube = FloatArray(8)
        val corners = Array(8) { FloatArray(3) }
        for (k in 0 until nz - 1) for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            var inside = 0
            for (c in 0 until 8) {
                val ci = i + (c and 1); val cj = j + (c shr 1 and 1); val ck = k + (c shr 2 and 1)
                cube[c] = v[(ck * ny + cj) * nx + ci]
                corners[c][0] = x0 + ci * STEP; corners[c][1] = y0 + cj * STEP; corners[c][2] = z0 + ck * STEP
                if (cube[c] < 0f) inside++
            }
            if (inside == 0 || inside == 8) continue
            for (t in TETRAHEDRA) tetrahedron(t, cube, corners, out)
        }
        return out
    }

    /** The surface inside one tetrahedron of a grid cube (its corners [t] among the cube's). */
    private fun tetrahedron(t: IntArray, value: FloatArray, corner: Array<FloatArray>, out: MutableList<Triangle>) {
        val inside = t.filter { value[it] < 0f }
        val outside = t.filter { value[it] >= 0f }
        fun cut(a: Int, b: Int): FloatArray {
            val k = value[a] / (value[a] - value[b])
            return floatArrayOf(
                corner[a][0] + (corner[b][0] - corner[a][0]) * k,
                corner[a][1] + (corner[b][1] - corner[a][1]) * k,
                corner[a][2] + (corner[b][2] - corner[a][2]) * k,
            )
        }
        when (inside.size) {
            1 -> emit(cut(inside[0], outside[0]), cut(inside[0], outside[1]), cut(inside[0], outside[2]), out)
            3 -> emit(cut(outside[0], inside[0]), cut(outside[0], inside[1]), cut(outside[0], inside[2]), out)
            2 -> {
                val a = cut(inside[0], outside[0]); val b = cut(inside[0], outside[1])
                val c = cut(inside[1], outside[0]); val d = cut(inside[1], outside[1])
                emit(a, b, c, out)
                emit(b, d, c, out)
            }
        }
    }

    /** One triangle, wound to face out of the rock, with the solid's smooth normal at each corner. */
    private fun emit(a: FloatArray, b: FloatArray, c: FloatArray, out: MutableList<Triangle>) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
        if (fx * fx + fy * fy + fz * fz < 1e-8f) return
        val na = normal(a); val nb = normal(b); val nc = normal(c)
        val outward = (na[0] + nb[0] + nc[0]) * fx + (na[1] + nb[1] + nc[1]) * fy + (na[2] + nb[2] + nc[2]) * fz > 0f
        val p = if (outward) floatArrayOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2])
            else floatArrayOf(a[0], a[1], a[2], c[0], c[1], c[2], b[0], b[1], b[2])
        val n = if (outward) na + nb + nc else na + nc + nb
        val y = (a[1] + b[1] + c[1]) / 3f
        val up = (na[1] + nb[1] + nc[1]) / 3f
        // Scrub grows in patches on the gentler parts of the top, with bare rock between.
        val mx = (a[0] + b[0] + c[0]) / 3f
        val mz = (a[2] + b[2] + c[2]) / 3f
        val patch = noise(mx * 0.12f + 17f, 3f, mz * 0.12f + 17f)
        val part = when {
            y < WET_LINE -> Part.WET
            up > 0.55f && y > height * 0.55f && patch > 0.38f -> Part.SCRUB
            else -> Part.CLIFF
        }
        out += Triangle(p, n, part)
    }

    /** Normals already worked out, by corner (each corner is shared by several triangles). */
    private val normals = HashMap<Long, FloatArray>()

    private fun normal(p: FloatArray): FloatArray {
        val key = ((p[0] * 64f).toLong() * 73856093L) xor ((p[1] * 64f).toLong() * 19349663L) xor ((p[2] * 64f).toLong() * 83492791L)
        return normals.getOrPut(key) { gradient(p) }
    }

    private fun gradient(p: FloatArray): FloatArray {
        val e = 0.5f
        val gx = distance(p[0] + e, p[1], p[2]) - distance(p[0] - e, p[1], p[2])
        val gy = distance(p[0], p[1] + e, p[2]) - distance(p[0], p[1] - e, p[2])
        val gz = distance(p[0], p[1], p[2] + e) - distance(p[0], p[1], p[2] - e)
        val l = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1e-6f } ?: 1f
        return floatArrayOf(gx / l, gy / l, gz / l)
    }

    /**
     * How far (x, y, z) is outside the rock (negative inside): the footprint, drawn in at the
     * waterline notch and towards the top, roughened, capped with a rounded top, minus the arch.
     */
    fun distance(x: Float, y: Float, z: Float): Float {
        val inFootprint = CityMap.inside(footprint, x, z)
        val edge = CityMap.edgeDistance(footprint, x, z)
        var d = if (inFootprint) -edge else edge
        val t = (y / height).coerceIn(0f, 1f)
        // Narrower towards the top, more on some sides than others; a little wider under the water.
        val lean = noise(x * 0.03f + 7f, 0f, z * 0.03f + 7f)
        d += t * t * radius * (0.18f + 0.3f * lean)
        if (y < -0.5f) d -= 0.8f
        // The wave-cut notch along the waterline.
        d += 1.4f * bump(y, 0.8f, 1.6f)
        // Rough limestone: big lumps and smaller knobs; mostly vertical grooves where rain has worn
        // the cliffs, and here and there a faint ledge where a harder layer stands out.
        val n1 = noise(x * 0.07f, y * 0.07f, z * 0.07f)
        val n2 = noise(x * 0.21f + 31f, y * 0.21f, z * 0.21f)
        val grooves = noise(x * 0.2f + 53f, y * 0.035f, z * 0.2f + 53f)
        val layer = noise(x * 0.05f + 91f, y * 0.05f, z * 0.05f)
        d += (n1 - 0.5f) * 3.6f + (n2 - 0.5f) * 1.3f + (grooves - 0.5f) * 3.2f
        d += 0.35f * sin(y * 0.6f + n1 * 7f) * (layer - 0.3f).coerceAtLeast(0f) * 2f
        // The top: a dome over the whole rock, rough too.
        val fromMiddle = sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) / max(radius, 1f)
        val top = y - (height - 0.16f * height * fromMiddle * fromMiddle + (n1 - 0.5f) * 3f)
        d = smoothMax(d, top, 3f)
        // Open below the sea floor (never seen).
        d = max(d, BOTTOM + 0.5f - y)
        if (arch) {
            // A tunnel at sea level across the rock: round-topped, as wide as it is high (the real one
            // is about 11 m; a little more here, so the light shows through from the Corniche).
            val across = (x - cx) * -archDz + (z - cz) * archDx
            val half = ARCH_SPAN / 2f
            val crown = ARCH_HEIGHT - half
            val r = if (y > crown) sqrt(across * across + (y - crown) * (y - crown)) - half else abs(across) - half
            val rough = (n2 - 0.5f) * 0.6f
            d = smoothMax(d, -(r + rough), 1f)
        }
        return d
    }

    /** 1 at [centre], falling smoothly to 0 [width] metres either side. */
    private fun bump(y: Float, centre: Float, width: Float): Float {
        val k = abs(y - centre) / width
        return if (k >= 1f) 0f else (1f - k * k) * (1f - k * k)
    }

    private fun smoothMax(a: Float, b: Float, k: Float): Float {
        val h = (0.5f - 0.5f * (b - a) / k).coerceIn(0f, 1f)
        return b + (a - b) * h + k * h * (1f - h)
    }

    /** Smooth value noise in 0..1, the same for the same rock every time. */
    private fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val fx = fade(x - xi); val fy = fade(y - yi); val fz = fade(z - zi)
        fun h(i: Int, j: Int, k: Int): Float {
            var n = i * 374761393 + j * 668265263 + k * 1274126177 + noiseSeed * 1442695041
            n = (n xor (n ushr 13)) * 1274126177
            return ((n xor (n ushr 16)) and 0xFFFFFF) / 16777215f
        }
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val x00 = lerp(h(xi, yi, zi), h(xi + 1, yi, zi), fx)
        val x10 = lerp(h(xi, yi + 1, zi), h(xi + 1, yi + 1, zi), fx)
        val x01 = lerp(h(xi, yi, zi + 1), h(xi + 1, yi, zi + 1), fx)
        val x11 = lerp(h(xi, yi + 1, zi + 1), h(xi + 1, yi + 1, zi + 1), fx)
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz)
    }

    private fun fade(t: Float) = t * t * (3f - 2f * t)

    companion object {
        /** Grid spacing for finding the surface, metres. */
        const val STEP = 1.5f
        /** How deep the rock goes below the sea (it's never seen lower). */
        const val BOTTOM = -3f
        /** Below this the rock is dark and wet from the waves. */
        const val WET_LINE = 1.6f
        /** The big Pigeon Rock's arch: how wide, and how high its top. */
        const val ARCH_SPAN = 14f
        const val ARCH_HEIGHT = 14f

        /** Each grid cube as six tetrahedra around its main diagonal (corner bits: x 1, y 2, z 4). */
        private val TETRAHEDRA = arrayOf(
            intArrayOf(0, 1, 3, 7), intArrayOf(0, 1, 5, 7), intArrayOf(0, 2, 3, 7),
            intArrayOf(0, 2, 6, 7), intArrayOf(0, 4, 5, 7), intArrayOf(0, 4, 6, 7),
        )
    }
}
