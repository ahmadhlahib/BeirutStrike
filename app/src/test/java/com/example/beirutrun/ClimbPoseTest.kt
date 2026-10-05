package com.example.beirutrun

import com.example.beirutrun.city.ClimbPose
import com.example.beirutrun.city.CrawlPose
import com.example.beirutrun.city.SoldierAnimator
import com.example.beirutrun.city.SoldierRig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The hand-made ladder climb works on the built-in soldier and every character in assets/models/characters/. */
class ClimbPoseTest {

    private val assets = File("src/main/assets")
    private val open: (String) -> ByteArray? = { path -> File(assets, path).takeIf { it.exists() }?.readBytes() }

    private val rigs: List<Pair<String, SoldierRig>> by lazy {
        val folders = File(assets, "models/characters").listFiles { f -> File(f, "character.glb").exists() }
            .orEmpty().sortedBy { it.name }
        listOf("soldier" to SoldierRig.load(null, emptyList(), open = open)) +
            folders.map { it.name to SoldierRig.load("models/characters/${it.name}", emptyList(), open = open) }
    }

    @Test
    fun handsOnTheRailsAndOneKneeUp() {
        for ((who, rig) in rigs) {
            val model = rig.model
            val bones = CrawlPose.Bones.detect(model)
            assertTrue("$who: has the bones to climb", ClimbPose(model, bones).usable)
            val anim = SoldierAnimator(rig)
            // A quarter of the way through the cycle (0.7 m climbed at 1 m/s × 0.175 s): one hand at its highest.
            repeat(7) { anim.update(0.025f, 1f, 0f, aiming = false, dead = false, skin = true, climbing = true) }
            fun y(name: String): Float {
                val m = FloatArray(16)
                anim.pose.nodeMatrix(model.nodeIndex(name), m)
                return m[13]
            }
            val shoulderL = y(bones.upperArmL.first); val shoulderR = y(bones.upperArmR.first)
            val handL = y(bones.lowerArmL.second); val handR = y(bones.lowerArmR.second)
            val kneeL = y(bones.upperLegL.second); val kneeR = y(bones.upperLegR.second)
            val hip = y(bones.upperLegL.first)
            println("$who: hands ${handL - shoulderL} / ${handR - shoulderR} above shoulders, knees ${hip - kneeL} / ${hip - kneeR} below hips")
            assertTrue("$who: both hands up on the rails", handL > shoulderL && handR > shoulderR)
            assertTrue("$who: one hand higher than the other", kotlin.math.abs(handL - handR) > 0.05f * rig.unit)
            assertTrue("$who: one knee raised higher than the other", kotlin.math.abs(kneeL - kneeR) > 0.1f * rig.unit)
            assertTrue("$who: the pose is skinned without NaNs", anim.ready && anim.pose.vertices.all { v -> v.all { it.isFinite() } })
        }
    }
}
