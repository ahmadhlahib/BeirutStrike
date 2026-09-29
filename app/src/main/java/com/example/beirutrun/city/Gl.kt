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

/** The one shader used for the whole city: optional texture, simple sun lighting and distance fog. */
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
    val uLightDir = GLES20.glGetUniformLocation(program, "uLightDir")
    val uFogColor = GLES20.glGetUniformLocation(program, "uFogColor")
    val uFog = GLES20.glGetUniformLocation(program, "uFog")
    val uTex = GLES20.glGetUniformLocation(program, "uTex")

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
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                gl_Position = uMvp * vec4(aPos, 1.0);
                vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
                vUv = aUv;
                // Pass the offset, not the distance: an offset interpolates correctly across big
                // triangles (like the ground), a distance does not.
                vFromEye = world.xyz - uEye;
            }
        """

        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uUseTex;
            uniform float uLit;
            uniform vec4 uColor;
            uniform vec3 uLightDir;
            uniform vec3 uFogColor;
            uniform vec2 uFog;
            varying vec3 vNormal;
            varying vec2 vUv;
            varying vec3 vFromEye;
            void main() {
                vec4 base = uColor;
                if (uUseTex > 0.5) base *= texture2D(uTex, vUv);
                if (base.a < 0.02) discard;
                float light = 1.0;
                if (uLit > 0.5) {
                    vec3 n = normalize(vNormal);
                    light = 0.52 + 0.4 * max(dot(n, uLightDir), 0.0) + 0.08 * n.y;
                }
                float fog = clamp((length(vFromEye) - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);
                gl_FragColor = vec4(mix(base.rgb * light, uFogColor, fog), base.a);
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

/** Triangles stored in a GPU buffer: position (3), normal (3), uv (2) per vertex. */
class Mesh(data: FloatArray) {
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

    fun draw(shader: CityShader) {
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

    /** Flat horizontal rectangle at height [y], facing up. */
    fun floor(x0: Float, z0: Float, x1: Float, z1: Float, y: Float) = quad(
        floatArrayOf(x0, y, z1), floatArrayOf(x1, y, z1), floatArrayOf(x1, y, z0), floatArrayOf(x0, y, z0),
        0f, 1f, 0f,
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
