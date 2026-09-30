package com.example.beirutrun.city

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A character standing on the character screen, idling with its rifle and turning slowly, drawn
 * with the game's own shader and skinning so it looks as it will in the city: in the team's
 * [uniform] and [gear] colours (characters with their own clothes keep them).
 */
class CharacterPreview(private val uniform: Int, private val gear: Int) : GLSurfaceView.Renderer {

    /** The character to show, set from any thread (null: nothing yet, e.g. still loading). */
    @Volatile var rig: SoldierRig? = null

    private lateinit var shader: CityShader
    private var shown: SoldierRig? = null
    private var anim: SoldierAnimator? = null
    private var meshes: List<DynamicMesh> = emptyList()
    private var textures: List<Int> = emptyList()
    private var lastFrame = 0L
    private var aspect = 1f
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: every old GL id is gone.
        shown = null
        anim = null
        shader = CityShader()
        // A dark stage behind the character, like the loadout cards.
        GLES20.glClearColor(0.063f, 0.078f, 0.094f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        lastFrame = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width / height.coerceAtLeast(1).toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val rig = rig ?: return
        if (rig !== shown) show(rig)
        val a = anim ?: return
        a.update(dt, 0f, 0f, aiming = false, dead = false, skin = true)
        meshes.forEachIndexed { i, m -> m.update(a.pose.vertices[i]) }

        // Camera a little in front of the character, looking at its chest; the whole body fits.
        Matrix.perspectiveM(projection, 0, 32f, aspect, 0.1f, 50f)
        Matrix.setLookAtM(view, 0, 0f, 1.05f, 4.6f, 0f, 0.92f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        Matrix.setIdentityM(model, 0)
        // Turns slowly, starting a little to its right so the face and the rifle both show.
        Matrix.rotateM(model, 0, 200f + now / 1000f * 25f, 0f, 1f, 0f)
        val s = rig.scale
        Matrix.scaleM(model, 0, s, s, s)
        Matrix.translateM(model, 0, 0f, -rig.footY, 0f)

        GLES20.glUseProgram(shader.program)
        GLES20.glEnableVertexAttribArray(shader.aPos)
        GLES20.glEnableVertexAttribArray(shader.aNormal)
        GLES20.glEnableVertexAttribArray(shader.aUv)
        GLES20.glUniform1i(shader.uTex, 0)
        GLES20.glUniform3f(shader.uEye, 0f, 1.05f, 4.6f)
        GLES20.glUniform3f(shader.uLightDir, 0.36f, 0.83f, 0.42f)
        GLES20.glUniform3f(shader.uFogColor, 0f, 0f, 0f)
        GLES20.glUniform2f(shader.uFog, 1000f, 2000f) // no haze this close
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
        GLES20.glUniform1f(shader.uLit, 1f)
        rig.model.primitives.forEachIndexed { i, p ->
            val color = when (rig.roles[i]) {
                MaterialRole.UNIFORM -> uniform
                MaterialRole.GEAR -> gear
                MaterialRole.DARK -> 0xFF1E1E1E.toInt()
                MaterialRole.SKIN -> 0xFFC9A07E.toInt()
                MaterialRole.HAIR -> 0xFF3B2A1C.toInt()
                MaterialRole.BROWN -> 0xFF4A3322.toInt()
                MaterialRole.OWN -> p.baseColor
            }
            GLES20.glUniform4f(
                shader.uColor,
                (color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f, (color and 0xFF) / 255f, 1f,
            )
            val texture = if (p.image >= 0) textures.getOrElse(p.image) { 0 } else 0
            if (texture != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            }
            GLES20.glUniform1f(shader.uUseTex, if (texture != 0) 1f else 0f)
            meshes[i].draw(shader)
        }
    }

    /** Swaps in another character: its skinning, GPU meshes and textures (the old ones are freed). */
    private fun show(rig: SoldierRig) {
        meshes.forEach { it.release() }
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        val a = SoldierAnimator(rig)
        meshes = rig.model.primitives.mapIndexed { i, p -> DynamicMesh(a.pose.vertices[i].size, p.indices) }
        textures = rig.model.images.map { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Textures.upload(it).also { _ -> it.recycle() } } ?: 0
        }
        anim = a
        shown = rig
    }
}
