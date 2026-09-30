package com.example.beirutrun.city

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A character standing on the character screen, idling with its rifle ([gun]) and turning slowly (or
 * turned by hand, see [drag]), drawn
 * with the game's own shader and skinning so it looks as it will in the city: in the team's
 * [uniform] and [gear] colours (characters with their own clothes keep them).
 */
class CharacterPreview(private val uniform: Int, private val gear: Int) : GLSurfaceView.Renderer {

    /** The character to show, set from any thread (null: nothing yet, e.g. still loading). */
    @Volatile var rig: SoldierRig? = null

    /** A dance to play once (its clip name, see Dance.clip), picked up on the next frame. */
    @Volatile private var pendingDance: String? = null

    /** Plays the dance [clip] once in the preview, then back to idling. */
    fun dance(clip: String) { pendingDance = clip }

    /** The 3D gun in the character's hands (set from any thread; null: none yet), put away while dancing. */
    @Volatile var gun: GunMesh? = null
    private var gunShown: GunMesh? = null
    private var gunMeshes: List<DynamicMesh> = emptyList()
    private var gunTextures: List<Int> = emptyList()
    private val bone = FloatArray(16)
    private val gunModel = FloatArray(16)

    /** The round stand the character is on: its dark base and the amber ring round its top. */
    private var standBase: DynamicMesh? = null
    private var standRim: DynamicMesh? = null
    private val standModel = FloatArray(16)

    /** Which way the character faces, degrees; starts a little to its right so the face and rifle show. */
    @Volatile private var yaw = 200f
    /** A finger is on the preview: it doesn't turn by itself until a while after it lets go. */
    @Volatile var holding = false
        set(value) { field = value; if (!value) releasedAt = SystemClock.uptimeMillis() }
    @Volatile private var releasedAt = 0L

    /** Turns the character by [degrees] (from a finger dragging across the preview). */
    fun drag(degrees: Float) { yaw += degrees }

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
        gunShown = null
        gunMeshes = emptyList()
        gunTextures = emptyList()
        shader = CityShader()
        standBase = Stand.base().let { (v, i) -> DynamicMesh(v.size, i).also { it.update(v) } }
        standRim = Stand.rim().let { (v, i) -> DynamicMesh(v.size, i).also { it.update(v) } }
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
        pendingDance?.let { pendingDance = null; a.dance(it) }
        a.update(dt, 0f, 0f, aiming = false, dead = false, skin = true)
        meshes.forEachIndexed { i, m -> m.update(a.pose.vertices[i]) }

        // Camera a little in front of the character, looking at its chest; the whole body fits.
        Matrix.perspectiveM(projection, 0, 32f, aspect, 0.1f, 50f)
        Matrix.setLookAtM(view, 0, 0f, 1.05f, 4.6f, 0f, 0.92f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, projection, 0, view, 0)
        Matrix.setIdentityM(model, 0)
        // Turns slowly by itself, except while (and shortly after) being turned by hand.
        if (!holding && now - releasedAt > AUTO_TURN_PAUSE_MS) yaw += dt * AUTO_TURN_SPEED
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
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
        GLES20.glUniform1f(shader.uLit, 1f)
        drawStand()
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
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
        if (a.dancing == null) drawGun(rig, a)
    }

    /** The stand under the feet (at y 0), in world units and turning with the character. */
    private fun drawStand() {
        Matrix.setIdentityM(standModel, 0)
        Matrix.rotateM(standModel, 0, yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, standModel, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, standModel, 0)
        GLES20.glUniform1f(shader.uUseTex, 0f)
        GLES20.glUniform4f(shader.uColor, 0.16f, 0.18f, 0.2f, 1f)
        standBase?.draw(shader)
        // Amber like the chosen character's outline and the dance buttons.
        GLES20.glUniform4f(shader.uColor, 1f, 0.7f, 0f, 1f)
        standRim?.draw(shader)
    }

    /**
     * The gun at the right hand, pointing the way the character faces, placed as the city does
     * it (CityRenderer's gunInHand): grip into the hand, muzzle forward, in model units.
     */
    private fun drawGun(rig: SoldierRig, a: SoldierAnimator) {
        val mesh = gun ?: return
        if (rig.wrist < 0) return
        if (mesh !== gunShown) uploadGun(mesh)
        a.pose.nodeMatrix(rig.wrist, bone)
        val u = rig.unit
        Matrix.translateM(gunModel, 0, model, 0, bone[12], bone[13], bone[14])
        Matrix.scaleM(gunModel, 0, u, u, u)
        Matrix.translateM(gunModel, 0, 0f, -0.06f, 0.02f)
        Matrix.rotateM(gunModel, 0, 180f, 0f, 1f, 0f)
        Matrix.translateM(gunModel, 0, 0f, -mesh.gripY, -mesh.gripZ)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, gunModel, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, gunModel, 0)
        mesh.parts.forEachIndexed { i, p ->
            val c = p.color
            GLES20.glUniform4f(shader.uColor, (c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
            val texture = if (p.texture >= 0) gunTextures.getOrElse(p.texture) { 0 } else 0
            if (texture != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            }
            GLES20.glUniform1f(shader.uUseTex, if (texture != 0) 1f else 0f)
            gunMeshes[i].draw(shader)
        }
    }

    private fun uploadGun(mesh: GunMesh) {
        gunMeshes.forEach { it.release() }
        if (gunTextures.isNotEmpty()) GLES20.glDeleteTextures(gunTextures.size, gunTextures.toIntArray(), 0)
        gunMeshes = mesh.parts.map { p -> DynamicMesh(p.vertices.size, p.indices).also { it.update(p.vertices) } }
        gunTextures = mesh.textures.map { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Textures.upload(it).also { _ -> it.recycle() } } ?: 0
        }
        gunShown = mesh
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

    private companion object {
        /** Degrees per second when turning by itself. */
        const val AUTO_TURN_SPEED = 25f
        /** How long it stays where the finger left it before turning by itself again. */
        const val AUTO_TURN_PAUSE_MS = 3000L
    }
}

/**
 * The preview's round stand as (vertices, indices) meshes in the game's layout (position, normal,
 * uv per vertex): a low disc whose top is at y 0, where the character's feet are.
 */
internal object Stand {
    const val RADIUS = 0.62f
    const val HEIGHT = 0.1f
    /** The amber ring round the top: from here out to (nearly) the edge. */
    const val RIM_INNER = 0.54f
    private const val SEGMENTS = 48

    /** The top face and the side, going down from the top edge. */
    fun base(): Pair<FloatArray, ShortArray> {
        val v = ArrayList<Float>()
        val idx = ArrayList<Short>()
        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) { v += listOf(x, y, z, nx, ny, nz, 0f, 0f) }
        // Top: a fan round the centre.
        vertex(0f, 0f, 0f, 0f, 1f, 0f)
        for (i in 0..SEGMENTS) { val (c, s) = around(i); vertex(c * RADIUS, 0f, s * RADIUS, 0f, 1f, 0f) }
        for (i in 1..SEGMENTS) idx += listOf(0, i, i + 1).map { it.toShort() }
        // Side: a band of quads facing out.
        val side = v.size / 8
        for (i in 0..SEGMENTS) {
            val (c, s) = around(i)
            vertex(c * RADIUS, 0f, s * RADIUS, c, 0f, s)
            vertex(c * RADIUS, -HEIGHT, s * RADIUS, c, 0f, s)
        }
        for (i in 0 until SEGMENTS) {
            val a = side + i * 2
            idx += listOf(a, a + 1, a + 2, a + 2, a + 1, a + 3).map { it.toShort() }
        }
        return v.toFloatArray() to idx.toShortArray()
    }

    /** A flat ring just above the top (so it doesn't flicker into it) near its edge. */
    fun rim(): Pair<FloatArray, ShortArray> {
        val v = ArrayList<Float>()
        val idx = ArrayList<Short>()
        val outer = RADIUS - 0.015f
        for (i in 0..SEGMENTS) {
            val (c, s) = around(i)
            v += listOf(c * RIM_INNER, 0.004f, s * RIM_INNER, 0f, 1f, 0f, 0f, 0f)
            v += listOf(c * outer, 0.004f, s * outer, 0f, 1f, 0f, 0f, 0f)
        }
        for (i in 0 until SEGMENTS) {
            val a = i * 2
            idx += listOf(a, a + 1, a + 2, a + 2, a + 1, a + 3).map { it.toShort() }
        }
        return v.toFloatArray() to idx.toShortArray()
    }

    private fun around(i: Int): Pair<Float, Float> {
        val t = i * 2.0 * Math.PI / SEGMENTS
        return Math.cos(t).toFloat() to Math.sin(t).toFloat()
    }
}
