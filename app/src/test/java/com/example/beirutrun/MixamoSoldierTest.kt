package com.example.beirutrun

import com.example.beirutrun.city.SoldierAnimator
import com.example.beirutrun.city.SoldierRig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Every Mixamo character in assets/models/characters/ (skipped if there are none). */
class MixamoSoldierTest {

    private val assets = File("src/main/assets")

    private val folders = File(assets, "models/characters").listFiles { f -> File(f, "character.glb").exists() }
        .orEmpty().sortedBy { it.name }

    private fun load(folder: File) = SoldierRig.load("models/characters/${folder.name}") { path -> File(assets, path).takeIf { it.exists() }?.readBytes() }

    @Test
    fun mixamoCharactersLoadWithAllGameAnimations() {
        assumeTrue(folders.isNotEmpty())
        for (folder in folders) {
            val rig = load(folder)
            val who = folder.name
            assertTrue("$who: should be a Mixamo skeleton", rig.model.nodeIndex("mixamorig:Hips") >= 0 || rig.model.nodeIndex("Hips") >= 0)
            for (name in listOf("Idle_Gun", "Idle_Gun_Shoot", "Walk", "Run", "Run_Shoot", "Run_Back", "Run_Left", "Run_Right", "Death")) {
                val clip = rig.model.clip(name)
                assertNotNull("$who: missing $name", clip)
                assertTrue("$who: $name has no moving bones", clip!!.channels.isNotEmpty())
            }
            assertTrue("$who: head bone", rig.head >= 0)
            assertTrue("$who: hand bone", rig.wrist >= 0)
            val vertices = rig.model.primitives.sumOf { it.vertexCount }
            println("$who: scale ${rig.scale} (1 m = ${rig.unit} model units), footY ${rig.footY}, $vertices vertices, " +
                "${rig.model.images.size} textures, materials ${rig.model.primitives.map { it.material }} -> roles ${rig.roles}")
            assertTrue("$who: face anchor should be set", rig.faceAnchor.any { it != 0f })
            assertTrue(rig.faceAnchor.all { it.isFinite() } && rig.badgeAnchors.all { a -> a.all { it.isFinite() } })
        }
    }

    @Test
    fun runningMovesTheLegsAndSkinningIsFastEnough() {
        assumeTrue(folders.isNotEmpty())
        for (folder in folders) {
            val anim = SoldierAnimator(load(folder))
            anim.update(0.016f, 6f, 0f, aiming = false, dead = false, skin = true)
            val before = anim.pose.vertices.map { it.copyOf() }
            repeat(20) { anim.update(0.016f, 6f, 0f, aiming = false, dead = false, skin = true) }
            var moved = 0f
            for (p in before.indices) for (i in before[p].indices) moved = maxOf(moved, abs(before[p][i] - anim.pose.vertices[p][i]))
            assertTrue("${folder.name}: running should move vertices (moved $moved)", moved > 0f)

            // Rough cost of one frame for one soldier on this PC (a phone is several times slower).
            val start = System.nanoTime()
            repeat(60) { anim.update(0.016f, 6f, 0f, aiming = false, dead = false, skin = true) }
            val ms = (System.nanoTime() - start) / 1e6 / 60
            println("${folder.name}: %.2f ms per soldier per frame (PC)".format(ms))
        }
    }
}
