package com.example.beirutrun.city

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * An army crawl made by hand, for soldier models without a crawl animation. The renderer lays the
 * standing model on its belly (model +y, the head, points forwards; model +z, the front, points
 * at the ground), so the pose is set in the standing model's space:
 * forward = +y, ground = +z, sky = -z.
 *
 * The chest is raised on the elbows with the head up looking ahead and the rifle out in front.
 * As the soldier moves, one knee is drawn up to the side while the opposite elbow reaches
 * forward, then the other side, like a real low crawl.
 */
class CrawlPose(model: SkinnedModel, bones: Bones) {

    /** Which bones make the crawl; each limb bone is paired with the next joint along it. */
    class Bones(
        val spine: List<Pair<String, String>>,
        val upperArmL: Pair<String, String>, val lowerArmL: Pair<String, String>,
        val upperArmR: Pair<String, String>, val lowerArmR: Pair<String, String>,
        val upperLegL: Pair<String, String>, val lowerLegL: Pair<String, String>,
        val upperLegR: Pair<String, String>, val lowerLegR: Pair<String, String>,
        /** Each foot and its toe tip, so the toes can point back along the ground. */
        val toeL: Pair<String, String>, val toeR: Pair<String, String>,
        /** Feet that hang off the root instead of the shins (Quaternius); null when they are children. */
        val footL: String? = null, val footR: String? = null,
    ) {
        companion object {
            val QUATERNIUS = Bones(
                spine = listOf("Torso" to "Chest", "Chest" to "Neck", "Neck" to "Head", "Head" to "Head_end"),
                upperArmL = "UpperArm.L" to "LowerArm.L", lowerArmL = "LowerArm.L" to "Wrist.L",
                upperArmR = "UpperArm.R" to "LowerArm.R", lowerArmR = "LowerArm.R" to "Wrist.R",
                upperLegL = "UpperLeg.L" to "LowerLeg.L", lowerLegL = "LowerLeg.L" to "LowerLeg.L_end",
                upperLegR = "UpperLeg.R" to "LowerLeg.R", lowerLegR = "LowerLeg.R" to "LowerLeg.R_end",
                toeL = "Foot.L" to "Foot.L_end", toeR = "Foot.R" to "Foot.R_end",
                footL = "Foot.L", footR = "Foot.R",
            )
            val MIXAMO = Bones(
                spine = listOf(
                    "mixamorig:Spine1" to "mixamorig:Spine2", "mixamorig:Spine2" to "mixamorig:Neck",
                    "mixamorig:Neck" to "mixamorig:Head", "mixamorig:Head" to "mixamorig:HeadTop_End",
                ),
                upperArmL = "mixamorig:LeftArm" to "mixamorig:LeftForeArm", lowerArmL = "mixamorig:LeftForeArm" to "mixamorig:LeftHand",
                upperArmR = "mixamorig:RightArm" to "mixamorig:RightForeArm", lowerArmR = "mixamorig:RightForeArm" to "mixamorig:RightHand",
                upperLegL = "mixamorig:LeftUpLeg" to "mixamorig:LeftLeg", lowerLegL = "mixamorig:LeftLeg" to "mixamorig:LeftFoot",
                upperLegR = "mixamorig:RightUpLeg" to "mixamorig:RightLeg", lowerLegR = "mixamorig:RightLeg" to "mixamorig:RightFoot",
                toeL = "mixamorig:LeftFoot" to "mixamorig:LeftToeBase", toeR = "mixamorig:RightFoot" to "mixamorig:RightToeBase",
            )

            /** The Mixamo skeleton exported without the "mixamorig:" prefix. */
            val MIXAMO_PLAIN = MIXAMO.withoutPrefix("mixamorig:")

            fun detect(model: SkinnedModel) = when {
                model.nodeIndex("mixamorig:Hips") >= 0 -> MIXAMO
                model.nodeIndex("RightHand") >= 0 && model.nodeIndex("Spine2") >= 0 -> MIXAMO_PLAIN
                else -> QUATERNIUS
            }
        }

        /** The same bones with [prefix] taken off every name. */
        fun withoutPrefix(prefix: String): Bones {
            fun p(pair: Pair<String, String>) = pair.first.removePrefix(prefix) to pair.second.removePrefix(prefix)
            return Bones(
                spine = spine.map(::p),
                upperArmL = p(upperArmL), lowerArmL = p(lowerArmL), upperArmR = p(upperArmR), lowerArmR = p(lowerArmR),
                upperLegL = p(upperLegL), lowerLegL = p(lowerLegL), upperLegR = p(upperLegR), lowerLegR = p(lowerLegR),
                toeL = p(toeL), toeR = p(toeR),
                footL = footL?.removePrefix(prefix), footR = footR?.removePrefix(prefix),
            )
        }
    }

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
     * left-and-right crawl stroke).
     */
    fun apply(pose: SkinnedPose, cycle: Float) {
        if (!usable) return
        val s = sin(2f * PI.toFloat() * cycle)
        // Which side is the soldier's left in model space (+x for most models).
        pose.nodeMatrix(upperArmL.bone, m)
        val left = if (m[12] != 0f) sign(m[12]) else 1f
        if (footL >= 0) pose.snapshot(lowerLegL.bone, shinBeforeL)
        if (footR >= 0) pose.snapshot(lowerLegR.bone, shinBeforeR)

        // Spine arched up off the ground on the elbows, head raised to look ahead.
        val lift = floatArrayOf(0.12f, 0.3f, 0.7f, 1.7f)
        for ((i, l) in spine.withIndex()) {
            val up = lift.getOrElse(i) { 1f }
            pose.aim(l.bone, l.tip, 0f, 1f, -up)
        }

        // Arms: elbows planted ahead of the shoulders, forearms flat on the ground angled in
        // to the rifle. The arm opposite the drawn-up knee reaches further forward.
        arm(pose, upperArmL, lowerArmL, left, reach = (1f - s) / 2f)
        arm(pose, upperArmR, lowerArmR, -left, reach = (1f + s) / 2f)

        // Legs: straight back, or knee drawn up to the side with the shin trailing behind.
        leg(pose, upperLegL, lowerLegL, left, bend = max(0f, s))
        leg(pose, upperLegR, lowerLegR, -left, bend = max(0f, -s))
        if (footL >= 0) pose.follow(footL, lowerLegL.bone, shinBeforeL)
        if (footR >= 0) pose.follow(footR, lowerLegR.bone, shinBeforeR)
        // Toes back along the ground, dug in a little.
        pose.aim(toeL.bone, toeL.tip, 0f, -1f, 0.35f)
        pose.aim(toeR.bone, toeR.tip, 0f, -1f, 0.35f)
    }

    private fun arm(pose: SkinnedPose, upper: Link, lower: Link, side: Float, reach: Float) {
        // Pulled back: elbow under the shoulder. Reaching: elbow well out in front.
        val fwd = 0.7f + 0.9f * reach
        pose.aim(upper.bone, upper.tip, side * 0.35f, fwd, 0.55f)
        pose.aim(lower.bone, lower.tip, -side * 0.55f, 1f, -0.02f)
    }

    private fun leg(pose: SkinnedPose, upper: Link, lower: Link, side: Float, bend: Float) {
        // Thigh swings out sideways along the ground as the knee comes up; the shin trails back.
        pose.aim(upper.bone, upper.tip, side * (0.15f + 0.95f * bend), -1f + 0.6f * bend, 0.05f)
        pose.aim(lower.bone, lower.tip, side * (0.05f - 0.1f * bend), -1f, 0.08f)
    }

    companion object {
        /** Metres travelled in one full crawl cycle (both sides). */
        const val STRIDE = 1.1f
    }
}
