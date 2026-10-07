package com.example.beirutrun.city

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** The one shader used for the whole city: optional texture, sun and sky lighting, reflections and distance fog. */
class CityShader {
    val program: Int = link(VERTEX, FRAGMENT)
    val aPos = GLES20.glGetAttribLocation(program, "aPos")
    val aNormal = GLES20.glGetAttribLocation(program, "aNormal")
    val aUv = GLES20.glGetAttribLocation(program, "aUv")
    val uMvp = GLES20.glGetUniformLocation(program, "uMvp")
    val uModel = GLES20.glGetUniformLocation(program, "uModel")
    val uEye = GLES20.glGetUniformLocation(program, "uEye")
    val uColor = GLES20.glGetUniformLocation(program, "uColor")
    val uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")
    val uLit = GLES20.glGetUniformLocation(program, "uLit")
    val uAo = GLES20.glGetUniformLocation(program, "uAo")
    val uShine = GLES20.glGetUniformLocation(program, "uShine")
    val uCutout = GLES20.glGetUniformLocation(program, "uCutout")
    val uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
    val uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
    val uFog = GLES20.glGetUniformLocation(program, "uFog")
    val uTex = GLES20.glGetUniformLocation(program, "uTex")
    /** The ground's height (maps with hills), for darkening walls near the ground under them. */
    val uGroundMap = GLES20.glGetUniformLocation(program, "uGroundMap")
    val uGround = GLES20.glGetUniformLocation(program, "uGround")
    val uGroundBase = GLES20.glGetUniformLocation(program, "uGroundBase")

    companion object {
        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform vec3 uEye;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            varying vec3 vNormal;
            varying vec2 vUv;
            varying vec3 vFromEye;
            varying float vHeight;
            varying highp vec2 vGround;
            uniform vec4 uGround;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                gl_Position = uMvp * vec4(aPos, 1.0);
                vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
                vUv = aUv;
                // Pass the offset, not the distance: an offset interpolates correctly across big
                // triangles (like the ground), a distance does not.
                vFromEye = world.xyz - uEye;
                vHeight = world.y;
                // Where this is on the ground's height map (hills; unused on flat maps).
                vGround = (world.xz - uGround.xy) * uGround.zw;
            }
        """

        /**
         * Sunlight is warm and the shade cool, with sky light from above. Walls ([uAo]) darken
         * near the ground, as light reaching the foot of a wall is blocked by the street around
         * it. Shiny surfaces ([uShine]: glass, water) reflect the sky, more at a glancing angle,
         * with a glint of the sun. Texture coordinates are high precision where the GPU has it,
         * so world-mapped textures stay sharp across a whole tile.
         */
        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uUseTex;
            uniform float uLit;
            uniform float uAo;
            uniform float uShine;
            uniform float uCutout;
            uniform vec4 uColor;
            uniform vec3 uLightDir;
            uniform vec3 uFogColor;
            uniform vec2 uFog;
            varying vec3 vNormal;
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            varying highp vec2 vUv;
            varying highp vec2 vGround;
            #else
            varying vec2 vUv;
            varying vec2 vGround;
            #endif
            varying vec3 vFromEye;
            varying float vHeight;
            // The ground's height map (hills): decimetres above [uGroundBase] in red (high byte) and
            // green (low byte); [uGroundBase] < -9000 when the map is flat.
            uniform sampler2D uGroundMap;
            uniform float uGroundBase;
            void main() {
                vec4 base = uColor;
                if (uUseTex > 0.5) base *= texture2D(uTex, vUv);
                if (base.a < max(uCutout, 0.02)) discard;
                vec3 color = base.rgb;
                if (uLit > 0.5) {
                    vec3 n = normalize(vNormal);
                    // Leaves are seen from both sides: light the side that faces the camera.
                    if (uCutout > 0.0 && !gl_FrontFacing) n = -n;
                    float sun = max(dot(n, uLightDir), 0.0);
                    float sky = 0.5 + 0.5 * n.y;
                    vec3 light = vec3(0.38, 0.41, 0.46) + vec3(0.14, 0.15, 0.17) * sky + vec3(0.58, 0.52, 0.42) * sun;
                    if (uAo > 0.5) {
                        // Height above the ground under this spot of wall (on hills, read from the map).
                        float above = vHeight;
                        if (uGroundBase > -9000.0) {
                            vec4 g = texture2D(uGroundMap, vGround);
                            above -= uGroundBase + (g.r * 65280.0 + g.g * 255.0) * 0.1;
                        }
                        float ao = mix(0.62, 1.0, smoothstep(0.0, 2.6, above));
                        light *= mix(1.0, ao, 1.0 - abs(n.y));
                    }
                    color *= light;
                    if (uShine > 0.0) {
                        vec3 v = normalize(vFromEye);
                        vec3 r = reflect(v, n);
                        vec3 zenith = vec3(0.30, 0.50, 0.76);
                        vec3 skyColor = r.y > 0.0 ? mix(uFogColor, zenith, sqrt(r.y)) : vec3(0.34, 0.33, 0.31);
                        float fresnel = 0.2 + 0.8 * pow(1.0 - max(dot(-v, n), 0.0), 3.0);
                        color = mix(color, skyColor, uShine * fresnel);
                        color += vec3(1.0, 0.95, 0.85) * (uShine * pow(max(dot(r, uLightDir), 0.0), 60.0));
                    }
                }
                float fog = clamp((length(vFromEye) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
                gl_FragColor = vec4(mix(color, uFogColor, fog), base.a);
            }
        """

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader error: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }

        private fun link(vs: String, fs: String): Int {
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Link error: " + GLES20.glGetProgramInfoLog(program) }
            return program
        }
    }
}

/** Triangles the city shader can draw. */
interface Drawable {
    fun draw(shader: CityShader)
}

/**
 * Triangles rewritten every frame (the passing cars and people), same vertex layout as [Mesh]:
 * [update] uploads the first floats of an array, [draw] draws them.
 */
class StreamMesh : Drawable {
    private val vbo: Int = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
    private var buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vertexCount = 0
    private var rangeFirst = 0
    private var rangeCount = -1

    /** Draws only [count] vertices from [first] from now on (count -1: all of them). */
    fun range(first: Int, count: Int) { rangeFirst = first; rangeCount = count }

    fun update(data: FloatArray, floats: Int) {
        vertexCount = floats / Mesh.FLOATS
        if (floats == 0) return
        if (buffer.capacity() < floats) buffer = ByteBuffer.allocateDirect(floats * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.position(0)
        buffer.put(data, 0, floats)
        buffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, buffer, GLES20.GL_STREAM_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun draw(shader: CityShader) {
        if (vertexCount == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(shader.aPos, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glVertexAttribPointer(shader.aNormal, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glVertexAttribPointer(shader.aUv, 2, GLES20.GL_FLOAT, false, 32, 24)
        val first = rangeFirst.coerceIn(0, vertexCount)
        val count = (if (rangeCount < 0) vertexCount else rangeCount).coerceAtMost(vertexCount - first)
        if (count > 0) GLES20.glDrawArrays(GLES20.GL_TRIANGLES, first, count)
    }
}

/** Triangles stored in a GPU buffer: position (3), normal (3), uv (2) per vertex. */
class Mesh(data: FloatArray) : Drawable {
    private val vertexCount = data.size / FLOATS
    private val vbo: Int

    init {
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(data).also { it.position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun draw(shader: CityShader) {
        if (vertexCount == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(shader.aPos, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glVertexAttribPointer(shader.aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, 12)
        GLES20.glVertexAttribPointer(shader.aUv, 2, GLES20.GL_FLOAT, false, STRIDE, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
    }

    fun release() = GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)

    companion object {
        const val FLOATS = 8
        private const val STRIDE = FLOATS * 4
    }
}

/**
 * An indexed mesh whose vertices change every frame (an animated character): same vertex layout
 * as [Mesh], with the triangle indices uploaded once.
 */
class DynamicMesh(vertexFloats: Int, indices: ShortArray) {
    private val indexCount = indices.size
    private val vbo: Int
    private val ibo: Int
    private val buffer = ByteBuffer.allocateDirect(vertexFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexFloats * 4, null, GLES20.GL_DYNAMIC_DRAW)
        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(indices).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun update(data: FloatArray) {
        buffer.position(0)
        buffer.put(data)
        buffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, data.size * 4, buffer)
    }

    fun draw(shader: CityShader) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(shader.aPos, 3, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glVertexAttribPointer(shader.aNormal, 3, GLES20.GL_FLOAT, false, 32, 12)
        GLES20.glVertexAttribPointer(shader.aUv, 2, GLES20.GL_FLOAT, false, 32, 24)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Frees the GPU buffers (GL thread); the mesh can't be drawn afterwards. */
    fun release() = GLES20.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
}

class MeshBuilder {
    private var data = FloatArray(4096)
    private var size = 0

    private fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float) {
        if (size + Mesh.FLOATS > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x; data[size++] = y; data[size++] = z
        data[size++] = nx; data[size++] = ny; data[size++] = nz
        data[size++] = u; data[size++] = v
    }

    /**
     * A quad given by its corners as seen from the front: bottom-left [a], bottom-right [b],
     * top-right [c], top-left [d]. The texture spans (0,0) top-left to ([uw],[vh]) bottom-right.
     */
    fun quad(
        a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray,
        nx: Float, ny: Float, nz: Float,
        uw: Float = 1f, vh: Float = 1f,
        fixedUv: Boolean = false,
    ) {
        fun v(p: FloatArray, u: Float, t: Float) =
            if (fixedUv) vertex(p[0], p[1], p[2], nx, ny, nz, 0.02f, 0.02f)
            else vertex(p[0], p[1], p[2], nx, ny, nz, u, t)
        v(a, 0f, vh); v(b, uw, vh); v(c, uw, 0f)
        v(a, 0f, vh); v(c, uw, 0f); v(d, 0f, 0f)
    }

    /** Flat horizontal rectangle at height [y], facing up; a texture repeats every [span] metres (0 = once). */
    fun floor(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, span: Float = 0f) = quad(
        floatArrayOf(x0, y, z1), floatArrayOf(x1, y, z1), floatArrayOf(x1, y, z0), floatArrayOf(x0, y, z0),
        0f, 1f, 0f,
        uw = if (span > 0f) (x1 - x0) / span else 1f, vh = if (span > 0f) (z1 - z0) / span else 1f,
    )

    /**
     * An axis-aligned box. With [cell] = 0 every face shows the whole texture; otherwise the texture
     * repeats every [cell] units on the walls (window grids) and the roof uses a single texel.
     */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, cell: Float = 0f) {
        val wx = if (cell > 0f) (x1 - x0) / cell else 1f
        val wz = if (cell > 0f) (z1 - z0) / cell else 1f
        val h = if (cell > 0f) (y1 - y0) / cell else 1f
        val roof = cell > 0f
        // South (+z), north (-z), east (+x), west (-x), top, bottom.
        quad(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), 0f, 0f, 1f, wx, h)
        quad(p(x1, y0, z0), p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), 0f, 0f, -1f, wx, h)
        quad(p(x1, y0, z1), p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), 1f, 0f, 0f, wz, h)
        quad(p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0), -1f, 0f, 0f, wz, h)
        quad(p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0), p(x0, y1, z0), 0f, 1f, 0f, fixedUv = roof)
        if (!roof) quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1), 0f, -1f, 0f)
    }

    /**
     * A box standing upright but turned to any direction on the ground: centred at (cx, cy, cz),
     * [halfAlong] each way along the unit direction (ax, az), [halfDepth] across it, [halfHeight] up and down.
     */
    fun turnedBox(
        cx: Float, cy: Float, cz: Float, ax: Float, az: Float,
        halfAlong: Float, halfHeight: Float, halfDepth: Float,
    ) {
        // Across the box: the along direction turned a quarter.
        val bx = -az; val bz = ax
        fun c(sa: Float, sy: Float, sb: Float) = p(
            cx + ax * halfAlong * sa + bx * halfDepth * sb, cy + halfHeight * sy, cz + az * halfAlong * sa + bz * halfDepth * sb,
        )
        quad(c(-1f, -1f, 1f), c(1f, -1f, 1f), c(1f, 1f, 1f), c(-1f, 1f, 1f), bx, 0f, bz)
        quad(c(1f, -1f, -1f), c(-1f, -1f, -1f), c(-1f, 1f, -1f), c(1f, 1f, -1f), -bx, 0f, -bz)
        quad(c(1f, -1f, 1f), c(1f, -1f, -1f), c(1f, 1f, -1f), c(1f, 1f, 1f), ax, 0f, az)
        quad(c(-1f, -1f, -1f), c(-1f, -1f, 1f), c(-1f, 1f, 1f), c(-1f, 1f, -1f), -ax, 0f, -az)
        quad(c(-1f, 1f, 1f), c(1f, 1f, 1f), c(1f, 1f, -1f), c(-1f, 1f, -1f), 0f, 1f, 0f)
        quad(c(-1f, -1f, -1f), c(1f, -1f, -1f), c(1f, -1f, 1f), c(-1f, -1f, 1f), 0f, -1f, 0f)
    }

    fun build() = Mesh(data.copyOf(size))

    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
}

/**
 * Asks for a 24-bit depth buffer so thin layers (sidewalks on the road, photos on their frames,
 * faces on heads) don't flicker in the distance; falls back to 16-bit on devices without one.
 */
class DepthConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (depth in intArrayOf(24, 16)) {
            val attribs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (egl.eglChooseConfig(display, attribs, configs, 1, count) && count[0] > 0) {
                configs[0]?.let { return it }
            }
        }
        throw IllegalStateException("No OpenGL ES 2 configuration available")
    }

    private companion object {
        const val EGL_OPENGL_ES2_BIT = 4
    }
}

object Textures {
    /** Uploads [bitmap]. Repeating textures must be power-of-two sized; they also get mipmaps. */
    fun upload(bitmap: Bitmap, repeat: Boolean = false): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val wrap = if (repeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            if (repeat) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        if (repeat) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return ids[0]
    }

    fun delete(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }
}
