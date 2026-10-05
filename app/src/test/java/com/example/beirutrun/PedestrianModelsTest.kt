package com.example.beirutrun

import com.example.beirutrun.city.Characters
import com.example.beirutrun.city.SoldierAnimator
import com.example.beirutrun.city.SoldierRig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every character model for people in the street (assets/models/pedestrians/, skipped if
 * there are none): it loads, can stand, walk and run, and is light enough to animate several
 * at once on a phone.
 */
class PedestrianModelsTest {

    private val assets = File("src/main/assets")

    private val folders = File(assets, "models/pedestrians").listFiles { f -> File(f, "character.glb").exists() }
        .orEmpty().sortedBy { it.name }

    @Test
    fun pedestriansLoadWithWalkRunAndIdle() {
        assumeTrue(folders.isNotEmpty())
        folders.forEachIndexed { i, folder ->
            val who = folder.name
            val rig = SoldierRig.load("models/pedestrians/$who") { path ->
                Characters.pedestrianFile(i, path).firstNotNullOfOrNull { File(assets, it).takeIf { f -> f.exists() }?.readBytes() }
            }
            assertTrue("$who: should be a Mixamo skeleton (Hips bone)", rig.model.nodeIndex("mixamorig:Hips") >= 0 || rig.model.nodeIndex("Hips") >= 0)
            for ((file, clip) in listOf("idle" to "Idle_Gun", "walk" to "Walk", "run" to "Run")) {
                assertNotNull("$who: missing $file.glb", rig.model.clip(clip))
            }
            val vertices = rig.model.primitives.sumOf { it.vertexCount }
            val size = folder.listFiles().orEmpty().sumOf { it.length() } / 1024
            println("$who: $vertices vertices, ${rig.model.images.size} textures, ${size} KB, materials ${rig.model.primitives.map { it.material }}")
            assertTrue("$who: $vertices vertices; keep street people under $MAX_VERTICES", vertices <= MAX_VERTICES)
            // It animates.
            val anim = SoldierAnimator(rig)
            anim.update(0.016f, 1.3f, 0f, aiming = false, dead = false, skin = true)
            assertTrue("$who: should pose", anim.ready)
        }
    }

    private companion object {
        /** Several are animated on the phone every frame, so they must be far lighter than players' characters. */
        const val MAX_VERTICES = 12_000
    }
}
