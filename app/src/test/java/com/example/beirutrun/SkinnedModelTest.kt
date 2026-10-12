package com.example.beirutrun

import com.example.beirutrun.city.Mat
import com.example.beirutrun.city.SkinnedModel
import com.example.beirutrun.city.SkinnedPose
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Loads the soldier shipped in assets/models/soldier.glb and checks posing and skinning. */
class SkinnedModelTest {

    companion object {
        lateinit var model: SkinnedModel

        @BeforeClass
        @JvmStatic
        fun load() {
            model = SkinnedModel.load(File("src/main/assets/models/soldier.glb").readBytes())
        }

        /** Bounding box (minX, minY, minZ, maxX, maxY, maxZ) of all skinned vertices. */
        fun bounds(pose: SkinnedPose): FloatArray {
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (v in pose.vertices) for (i in v.indices step 8) for (k in 0..2) {
                b[k] = minOf(b[k], v[i + k]); b[k + 3] = maxOf(b[k + 3], v[i + k])
            }
            return b
        }
    }

    @Test
    fun hasSkeletonAndGameAnimations() {
        assertTrue(model.primitives.isNotEmpty())
        assertTrue(model.skins.isNotEmpty())
        for (name in listOf("Idle_Gun", "Walk", "Run", "Run_Shoot", "Run_Back", "Run_Left", "Run_Right", "Death", "Idle_Gun_Shoot")) {
            assertNotNull("missing clip $name", model.clip(name))
        }
        for (bone in listOf("Head", "Chest", "UpperArm.L", "UpperArm.R", "Wrist.R")) {
            assertTrue("missing bone $bone", model.nodeIndex(bone) >= 0)
        }
    }

    @Test
    fun restPoseIsAPersonSizedUprightFigure() {
        val pose = SkinnedPose(model)
        pose.update(model.clip("Idle_Gun"), 0f, null, 0f, 1f)
        pose.skin()
        val b = bounds(pose)
        val w = b[3] - b[0]; val h = b[4] - b[1]; val d = b[5] - b[2]
        println("idle bounds: x ${b[0]}..${b[3]}  y ${b[1]}..${b[4]}  z ${b[2]}..${b[5]}  (w $w h $h d $d)")
        assertTrue("finite", b.all { it.isFinite() })
        // Taller than wide or deep: the model stands up along +y.
        assertTrue(h > w && h > d)

        // Which way does the face point? Compare the head and the hips.
        val m = FloatArray(16)
        val head = FloatArray(3); val hips = FloatArray(3); val nose = FloatArray(3)
        pose.nodeMatrix(model.nodeIndex("Head"), m); Mat.transformPoint(m, 0f, 0f, 0f, head)
        pose.nodeMatrix(model.nodeIndex("Hips"), m); Mat.transformPoint(m, 0f, 0f, 0f, hips)
        // The head mesh sticks out furthest on the face side.
        val headPrims = model.primitives.indices.filter { model.primitives[it].material == "Skin" }
        var maxZ = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        for (p in headPrims) { val v = pose.vertices[p]; for (i in v.indices step 8) if (v[i + 1] > head[1] - 0.05f * h) { maxZ = maxOf(maxZ, v[i + 2]); minZ = minOf(minZ, v[i + 2]) } }
        nose[2] = if (abs(maxZ - head[2]) > abs(minZ - head[2])) maxZ else minZ
        println("head ${head.toList()} hips ${hips.toList()} face extends to z=${nose[2]} (head z ${head[2]})")
        pose.nodeMatrix(model.nodeIndex("Wrist.R"), m); val wrist = FloatArray(3); Mat.transformPoint(m, 0f, 0f, 0f, wrist)
        pose.nodeMatrix(model.nodeIndex("UpperArm.R"), m); val arm = FloatArray(3); Mat.transformPoint(m, 0f, 0f, 0f, arm)
        println("wrist.R ${wrist.toList()} upperArm.R ${arm.toList()}")
    }

    @Test
    fun runPoseMovesTheLegs() {
        val a = SkinnedPose(model)
        val run = model.clip("Run")!!
        a.update(run, 0f, null, 0f, 1f); a.skin()
        val first = a.vertices.map { it.copyOf() }
        a.update(run, run.duration / 2f, null, 0f, 1f); a.skin()
        var moved = 0f
        for (p in first.indices) for (i in first[p].indices) moved = maxOf(moved, abs(first[p][i] - a.vertices[p][i]))
        assertTrue("run animation should move vertices, moved $moved", moved > 0f)

        // Cross-fading halfway between two clips stays finite.
        a.update(model.clip("Walk"), 0.3f, run, 0.2f, 0.5f); a.skin()
        assertTrue(a.vertices.all { v -> v.all { it.isFinite() } })
    }

    /**
     * Skinning on the GPU puts every vertex exactly where skinning on the CPU does: the bone
     * palettes, used the way the city shader uses them, give the same positions and normals.
     * Checked for every character the game ships, mid-run, and that each fits the shader's bones.
     */
    @Test
    fun gpuPalettesSkinLikeTheCpu() {
        val assets = File("src/main/assets")
        val open = { path: String -> File(assets, path).takeIf { it.exists() }?.readBytes() }
        val folders = listOf(null) + listOf("models/characters", "models/pedestrians").flatMap { dir ->
            File(assets, dir).listFiles { f -> File(f, "character.glb").exists() }.orEmpty().map { "$dir/${it.name}" }
        }
        for (folder in folders) {
            val rig = com.example.beirutrun.city.SoldierRig.load(folder, open = open)
            val m = rig.model
            // CityRenderer.GPU_BONES: the shader's room for bones.
            assertTrue("$folder has ${m.maxJoints} bones", m.maxJoints <= 72)
            val pose = SkinnedPose(m)
            val run = m.clip("Run") ?: m.clip("Idle_Gun")
            pose.update(run, (run?.duration ?: 0f) * 0.37f, null, 0f, 1f)
            pose.skin()
            var worst = 0f
            for ((pi, p) in m.primitives.withIndex()) {
                val palette = pose.palettes[p.skin]
                val cpu = pose.vertices[pi]
                for (v in 0 until p.vertexCount) {
                    for (k in 0..2) {
                        var pos = 0f
                        var nor = 0f
                        for (s in 0..3) {
                            val w = p.weights[v * 4 + s]
                            val row = p.joints[v * 4 + s] * 12 + k * 4
                            pos += w * (palette[row] * p.positions[v * 3] + palette[row + 1] * p.positions[v * 3 + 1] +
                                palette[row + 2] * p.positions[v * 3 + 2] + palette[row + 3])
                            nor += w * (palette[row] * p.normals[v * 3] + palette[row + 1] * p.normals[v * 3 + 1] +
                                palette[row + 2] * p.normals[v * 3 + 2])
                        }
                        worst = maxOf(worst, abs(pos - cpu[v * 8 + k]) * rig.scale, abs(nor - cpu[v * 8 + 3 + k]))
                    }
                }
            }
            println("${folder ?: "soldier"}: ${m.maxJoints} bones, GPU vs CPU differ by at most $worst")
            assertTrue("${folder ?: "soldier"} differs by $worst", worst < 1e-4f)
        }
    }
}
