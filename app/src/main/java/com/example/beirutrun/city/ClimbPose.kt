package com.example.beirutrun.city

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * Climbing a ladder, made by hand like [CrawlPose] (none of the models has a climbing animation).
 * The soldier stands upright facing the wall, in the standing model's space: up = +y, the wall
 * (the front) = +z.
 *
 * Both hands hold the rails, one higher than the other, and one knee is raised to the next rung
 * while the other leg stands on a lower one. The hand and the foot on opposite sides move
 * together, then the other pair, the way a person climbs a ladder.
 */
class ClimbPose(model: SkinnedModel, bones: CrawlPose.Bones) {

    private class Link(val bone: Int, val tip: Int)

    private fun link(model: SkinnedModel, p: Pair<String, String>) = Link(model.nodeIndex(p.first), model.nodeIndex(p.second))

    private val spine = bones.spine.map { link(model, it) }
    private val upperArmL = link(model, bones.upperArmL)
    private val lowerArmL = link(model, bones.lowerArmL)
    private val upperArmR = link(model, bones.upperArmR)
    private val lowerArmR = link(model, bones.lowerArmR)
    private val upperLegL = link(model, bones.upperLegL)
    private val lowerLegL = link(model, bones.lowerLegL)
    private val upperLegR = link(model, bones.upperLegR)
    private val lowerLegR = link(model, bones.lowerLegR)
    private val toeL = link(model, bones.toeL)
    private val toeR = link(model, bones.toeR)
    private val footL = bones.footL?.let(model::nodeIndex) ?: -1
    private val footR = bones.footR?.let(model::nodeIndex) ?: -1

    /** True when the model has the bones this needs. */
    val usable = (spine + listOf(upperArmL, lowerArmL, upperArmR, lowerArmR, upperLegL, lowerLegL, upperLegR, lowerLegR))
        .all { it.bone >= 0 && it.tip >= 0 }

    private val shinBeforeL = FloatArray(16)
    private val shinBeforeR = FloatArray(16)
    private val m = FloatArray(16)

    /**
     * Poses [pose] (already updated with a base clip, not yet skinned) at [cycle] (0..1, one full
     * left-and-right climbing step).
     */
    fun apply(pose: SkinnedPose, cycle: Float) {
        if (!usable) return
        // s > 0: left hand up and right knee up; s < 0: the other pair.
        val s = sin(2f * PI.toFloat() * cycle)
        pose.nodeMatrix(upperArmL.bone, m)
        val left = if (m[12] != 0f) sign(m[12]) else 1f
        if (footL >= 0) pose.snapshot(lowerLegL.bone, shinBeforeL)
        if (footR >= 0) pose.snapshot(lowerLegR.bone, shinBeforeR)

        // Upright, leaning in a little towards the ladder; the head tipped back to look up it.
        val lean = floatArrayOf(0.12f, 0.1f, 0.02f, -0.18f)
        for ((i, l) in spine.withIndex()) pose.aim(l.bone, l.tip, 0f, 1f, lean.getOrElse(i) { 0f })

        arm(pose, upperArmL, lowerArmL, left, high = max(0f, s))
        arm(pose, upperArmR, lowerArmR, -left, high = max(0f, -s))
        leg(pose, upperLegL, lowerLegL, left, raised = max(0f, -s))
        leg(pose, upperLegR, lowerLegR, -left, raised = max(0f, s))
        if (footL >= 0) pose.follow(footL, lowerLegL.bone, shinBeforeL)
        if (footR >= 0) pose.follow(footR, lowerLegR.bone, shinBeforeR)
        // Feet flat on the rungs, toes towards the wall.
        pose.aim(toeL.bone, toeL.tip, 0f, -0.15f, 1f)
        pose.aim(toeR.bone, toeR.tip, 0f, -0.15f, 1f)
    }

    /** An arm up to a rail in front of the shoulder: higher, and straighter, as it reaches up. */
    private fun arm(pose: SkinnedPose, upper: Link, lower: Link, side: Float, high: Float) {
        pose.aim(upper.bone, upper.tip, side * 0.22f, 0.25f + 0.75f * high, 0.75f - 0.25f * high)
        pose.aim(lower.bone, lower.tip, -side * 0.08f, 0.7f + 0.3f * high, 0.35f - 0.15f * high)
    }

    /** A leg standing on a rung below, or with the knee raised to the next one in front. */
    private fun leg(pose: SkinnedPose, upper: Link, lower: Link, side: Float, raised: Float) {
        pose.aim(upper.bone, upper.tip, side * 0.06f, -1f + 0.75f * raised, 0.12f + 0.8f * raised)
        pose.aim(lower.bone, lower.tip, 0f, -1f, 0.08f - 0.1f * raised)
    }

    companion object {
        /** Metres climbed in one full cycle (a step with each foot). */
        const val STRIDE = 0.7f
    }
}
