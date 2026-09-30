package com.example.beirutrun.city

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What a soldier looks like: uniform and gear colours for their team, plus the flag badge texture
 * (0 = none) drawn on the back of the shirt.
 */
class SoldierLook(val uniform: Int, val gear: Int, val badgeTexture: Int)

/** What part of the soldier a material is, which decides its colour. */
enum class MaterialRole { UNIFORM, GEAR, DARK, SKIN, HAIR, BROWN, OWN }

/** The bone names a soldier model uses for the things the game attaches to it. */
class SoldierBones(val head: String, val wrist: String, val armL: String, val armR: String, val chest: String) {
    companion object {
        /** Quaternius "Soldier" (the built-in model). */
        val QUATERNIUS = SoldierBones("Head", "Wrist.R", "UpperArm.L", "UpperArm.R", "Chest")
        /** Any Mixamo character (all use the same "mixamorig:" skeleton). */
        val MIXAMO = SoldierBones("mixamorig:Head", "mixamorig:RightHand", "mixamorig:LeftArm", "mixamorig:RightArm", "mixamorig:Spine2")
        /** The same skeleton exported without the "mixamorig:" prefix (e.g. an uploaded avatar). */
        val MIXAMO_PLAIN = SoldierBones("Head", "RightHand", "LeftArm", "RightArm", "Spine2")

        fun detect(model: SkinnedModel) = when {
            model.nodeIndex("mixamorig:Hips") >= 0 -> MIXAMO
            model.nodeIndex("RightHand") >= 0 && model.nodeIndex("Spine2") >= 0 -> MIXAMO_PLAIN
            else -> QUATERNIUS
        }

        /** The hips bone, which carries a Mixamo clip's walking motion, whichever naming the model uses. */
        fun hipsOf(model: SkinnedModel) = if (model.nodeIndex("mixamorig:Hips") >= 0) "mixamorig:Hips" else "Hips"
    }
}

/**
 * Where things attach to the soldier model, worked out once from the idle pose, and how big the
 * model is. Each anchor is a matrix in its bone's own space: bone matrix × anchor gives the
 * thing's model-space transform, so it follows the bone as the soldier animates.
 *
 * Models come in any units (Mixamo uses centimetres): [scale] turns model units into metres so
 * every soldier is [HEIGHT] tall, and [unit] is one metre in model units.
 */
class SoldierRig(val model: SkinnedModel, bones: SoldierBones = SoldierBones.detect(model)) {
    val head = model.nodeIndex(bones.head)
    val wrist = model.nodeIndex(bones.wrist)
    val chest = model.nodeIndex(bones.chest)

    /** Model units → metres, and the model's feet height (to stand it on the ground). */
    val scale: Float
    val footY: Float
    val unit: Float get() = 1f / scale

    /** Face photo: a disc just in front of the face, facing forward (+z in model space). */
    val faceAnchor = FloatArray(16)

    /** Bones carrying the team flag: the chest bone, for a flag on the back of the shirt. */
    val badgeBones = listOf(chest)
    val badgeAnchors = listOf(FloatArray(16))

    /** Colour role of each primitive (see [roleOf]). */
    val roles: List<MaterialRole> = model.primitives.map { roleOf(it.material, it.image >= 0) }

    init {
        val pose = SkinnedPose(model)
        pose.update(model.clip(IDLE), 0f, null, 0f, 1f)
        pose.skin()

        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (v in pose.vertices) for (i in v.indices step 8) { minY = min(minY, v[i + 1]); maxY = max(maxY, v[i + 1]) }
        scale = if (maxY > minY) HEIGHT / (maxY - minY) else 1f
        footY = if (minY < Float.MAX_VALUE) minY else 0f
        val u = unit

        // The face: the front-most vertices around the head bone, above its base.
        if (head >= 0) {
            val headM = FloatArray(16).also { pose.nodeMatrix(head, it) }
            val hx = headM[12]; val hy = headM[13]; val hz = headM[14]
            fun near(v: FloatArray, i: Int) = v[i + 1] > hy - 0.02f * u && hypot(v[i] - hx, v[i + 2] - hz) < 0.2f * u
            var frontZ = -Float.MAX_VALUE
            for (v in pose.vertices) for (i in v.indices step 8) if (near(v, i)) frontZ = max(frontZ, v[i + 2])
            var sx = 0f; var sy = 0f; var count = 0
            for (v in pose.vertices) for (i in v.indices step 8) {
                if (near(v, i) && v[i + 2] > frontZ - 0.06f * u) { sx += v[i]; sy += v[i + 1]; count++ }
            }
            val faceX = if (count > 0) sx / count else hx
            val faceY = if (count > 0) sy / count else hy + 0.08f * u
            if (frontZ > -Float.MAX_VALUE) anchor(faceAnchor, headM, faceX, faceY, frontZ + 0.012f * u, 0f, FACE_SIZE * u)
        }

        // The team flag: in the middle of the upper back, just behind the shirt, facing backwards
        // (the character faces +z, so its back is the lowest z at that height).
        if (chest >= 0) {
            val chestM = FloatArray(16).also { pose.nodeMatrix(chest, it) }
            val x = chestM[12]
            val y = chestM[13] + 0.02f * u
            var back = chestM[14]
            for (v in pose.vertices) for (i in v.indices step 8) {
                if (abs(v[i + 1] - y) < 0.08f * u && abs(v[i] - x) < 0.1f * u) back = min(back, v[i + 2])
            }
            anchor(badgeAnchors[0], chestM, x, y, back - 0.012f * u, 180f, BADGE_SIZE * u)
        }
    }

    /** Anchor = inverse(bone) × translate(x, y, z) × rotateY(yawDegrees) × scale(size). */
    private fun anchor(out: FloatArray, bone: FloatArray, x: Float, y: Float, z: Float, yawDegrees: Float, size: Float) {
        val placed = FloatArray(16)
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val c = kotlin.math.cos(yaw).toFloat()
        val s = kotlin.math.sin(yaw).toFloat()
        // Columns: rotated x axis, y axis, rotated z axis (each scaled), then position.
        placed[0] = c * size; placed[1] = 0f; placed[2] = -s * size; placed[3] = 0f
        placed[4] = 0f; placed[5] = size; placed[6] = 0f; placed[7] = 0f
        placed[8] = s * size; placed[9] = 0f; placed[10] = c * size; placed[11] = 0f
        placed[12] = x; placed[13] = y; placed[14] = z; placed[15] = 1f
        val inverse = FloatArray(16)
        invertRigid(bone, inverse)
        Mat.multiply(out, 0, inverse, 0, placed, 0)
    }

    companion object {
        const val IDLE = "Idle_Gun"
        /** Every soldier is scaled to this height, metres. */
        const val HEIGHT = 1.82f
        private const val FACE_SIZE = 0.24f
        /** Width of the flag on the back, metres. */
        private const val BADGE_SIZE = 0.22f

        /**
         * Guesses what a material is from its name. Textured materials keep their texture (a
         * realistic soldier's clothes are painted into it); otherwise uniform and gear follow the
         * team colours.
         */
        fun roleOf(material: String, textured: Boolean): MaterialRole {
            val m = material.lowercase()
            return when {
                textured -> MaterialRole.OWN
                listOf("swat", "surface", "highlimb", "body", "cloth", "uniform", "shirt", "pant", "top").any { it in m } -> MaterialRole.UNIFORM
                listOf("grey", "gray", "joint", "vest", "gear", "boot", "armor", "armour", "strap").any { it in m } -> MaterialRole.GEAR
                "black" in m -> MaterialRole.DARK
                "skin" in m -> MaterialRole.SKIN
                "hair" in m -> MaterialRole.HAIR
                "brown" in m -> MaterialRole.BROWN
                else -> MaterialRole.OWN
            }
        }

        /**
         * Mixamo animation files (one clip each, see assets/models/mixamo/) and the game names
         * they play as. The same clip can serve two names.
         */
        val MIXAMO_CLIPS = listOf(
            "idle" to "Idle_Gun", "shoot" to "Idle_Gun_Shoot", "walk" to "Walk",
            "run" to "Run", "run" to "Run_Shoot", "run_back" to "Run_Back",
            "strafe_left" to "Run_Left", "strafe_right" to "Run_Right",
            "death" to "Death", "hit" to "HitRecieve",
            // Optional extras: if these files are added, jumping and crawling use them.
            "jump" to "Jump", "crawl" to "Crawl",
        )

        /** Inverse of a rotation(+uniform scale) + translation matrix. */
        fun invertRigid(m: FloatArray, out: FloatArray) {
            val scale2 = m[0] * m[0] + m[1] * m[1] + m[2] * m[2]
            val inv = if (scale2 > 0f) 1f / scale2 else 1f
            // Transpose the 3x3 part (divided by scale²), then move the translation back.
            for (col in 0..2) for (row in 0..2) out[col * 4 + row] = m[row * 4 + col] * inv
            out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
            val tx = m[12]; val ty = m[13]; val tz = m[14]
            out[12] = -(out[0] * tx + out[4] * ty + out[8] * tz)
            out[13] = -(out[1] * tx + out[5] * ty + out[9] * tz)
            out[14] = -(out[2] * tx + out[6] * ty + out[10] * tz)
        }

        /**
         * Loads a character (see Characters): from [folder], a Mixamo character (`character.glb`)
         * with its animation files (named as in [MIXAMO_CLIPS]); for a null folder, or one
         * without a character, the built-in Quaternius soldier. [open] reads an asset's bytes, or
         * returns null if it's missing.
         */
        fun load(folder: String? = null, dances: List<Dance> = emptyList(), open: (String) -> ByteArray?): SoldierRig {
            val character = folder?.let { open("$it/character.glb") }
            if (character != null) {
                val clips = LinkedHashMap<String, SkinnedModel>()
                // Each file is read once even when it serves two clip names; missing files are skipped.
                val loaded = HashMap<String, SkinnedModel?>()
                for ((file, name) in MIXAMO_CLIPS) {
                    val anim = if (file in loaded) loaded[file]
                    else open("$folder/$file.glb")?.let(SkinnedModel::load).also { loaded[file] = it }
                    if (anim != null) clips[name] = anim
                }
                // Dances play as "Dance:<file>" (see Dance.clip).
                for (d in dances) open("$folder/${d.file}.glb")?.let(SkinnedModel::load)?.let { clips[d.clip] = it }
                val model = SkinnedModel.load(character)
                return SoldierRig(model.withClips(clips, rootBone = SoldierBones.hipsOf(model)))
            }
            return SoldierRig(SkinnedModel.load(open("models/soldier.glb") ?: error("No soldier model in assets")))
        }
    }
}

/**
 * One soldier on screen: picks the animation from what they're doing, cross-fades between clips,
 * and keeps a skinned pose ready to draw.
 */
class SoldierAnimator(val rig: SoldierRig) {
    val pose = SkinnedPose(rig.model)

    /** The model has no crawl animation, so crawling lays the body flat and poses it by hand ([crawl]). */
    val lieDownForCrawl = rig.model.clip("Crawl") == null
    private val crawl = if (lieDownForCrawl) CrawlPose(rig.model, CrawlPose.Bones.detect(rig.model)) else null
    /** Where in the crawl stroke the soldier is (0..1); moves with the distance crawled. */
    private var crawlCycle = 0.25f
    private val hasJump = rig.model.clip("Jump") != null
    private var clip: SkinnedModel.Clip? = rig.model.clip(SoldierRig.IDLE)
    private var time = 0f
    private var fromClip: SkinnedModel.Clip? = null
    private var fromTime = 0f
    private var fade = 1f
    private var looping = true

    /** A dance playing instead of the usual animation (see [dance]); null = none. */
    var dancing: String? = null
        private set
    private var danceLoops = false
    private var danceRestart = false

    /**
     * Plays the dance clip [clipName] (see Dance.clip) once, or over and over if [loop]; asking
     * for the same dance again starts it from the beginning, and null stops dancing.
     */
    fun dance(clipName: String?, loop: Boolean = false) {
        dancing = clipName?.takeIf { rig.model.clip(it) != null }
        danceLoops = loop
        danceRestart = dancing != null
    }

    /** Goes up each time [pose] is re-skinned, so the GPU copy knows when to refresh. */
    var version = 0
        private set

    /** True once [pose] has been skinned at least once. */
    var ready = false
        private set

    /**
     * Advances the animation. [speed] is metres per second; [moveAngle] is the direction of travel
     * relative to where the soldier faces (radians, 0 = forwards, positive = to their right).
     */
    fun update(
        dt: Float, speed: Float, moveAngle: Float, aiming: Boolean, dead: Boolean, skin: Boolean,
        prone: Boolean = false, airborne: Boolean = false,
    ) {
        val dance = dancing?.takeIf { !dead }
        val (name, loop, fixedRate) = if (dance != null) Triple(dance, danceLoops, 1f)
            else choose(speed, moveAngle, aiming, dead, prone, airborne)
        val wanted = rig.model.clip(name)
        if (danceRestart && wanted != null) {
            // Start the dance from its first step (even if it's the one already playing).
            danceRestart = false
            fromClip = clip; fromTime = time; clip = wanted; time = 0f; fade = 0f; looping = loop
        }
        // Clips that originally walked the body forward play at the rate that makes their feet
        // match the player's real speed; in-place clips use the fixed rates in choose().
        val natural = (wanted?.rootSpeed ?: 0f) * rig.scale
        val rate = if (natural > 0.1f && speed >= 0.3f) (speed / natural).coerceIn(0.5f, 2.2f) else fixedRate
        if (wanted != null && wanted !== clip) {
            fromClip = clip
            fromTime = time
            clip = wanted
            time = 0f
            fade = 0f
            looping = loop
        }
        val c = clip
        if (c != null) {
            time += dt * rate
            if (looping && c.duration > 0f) time %= c.duration else time = min(time, c.duration)
            // A dance played once ends with its last step; then back to the usual animation.
            if (dance != null && !looping && time >= c.duration) dancing = null
        }
        fromClip?.let { f -> fromTime = (fromTime + dt) % max(f.duration, 0.01f) }
        val crawling = prone && !dead && crawl?.usable == true
        if (crawling && speed >= 0.2f) crawlCycle = (crawlCycle + dt * speed / CrawlPose.STRIDE) % 1f
        fade = min(1f, fade + dt / CROSSFADE)
        if (fade >= 1f) fromClip = null
        if (skin) {
            pose.update(clip, time, fromClip, fromTime, fade)
            if (crawling) crawl?.apply(pose, crawlCycle)
            pose.skin()
            version++
            ready = true
        }
    }

    private fun choose(
        speed: Float, moveAngle: Float, aiming: Boolean, dead: Boolean, prone: Boolean, airborne: Boolean,
    ): Triple<String, Boolean, Float> {
        if (dead) return Triple("Death", false, 1f)
        if (prone) {
            if (!lieDownForCrawl) return Triple("Crawl", true, if (speed < 0.2f) 0f else (speed / CRAWL_CLIP_SPEED).coerceIn(0.5f, 2.2f))
            // Lying flat: the idle pose is the base the hand-made crawl (CrawlPose) is set on.
            return Triple(SoldierRig.IDLE, true, 0.3f)
        }
        if (airborne && hasJump) return Triple("Jump", false, 1.2f)
        if (speed < 0.3f) return Triple(if (aiming) "Idle_Gun_Shoot" else SoldierRig.IDLE, true, 1f)
        val a = abs(moveAngle)
        return when {
            a < PI / 4 && speed < RUN_THRESHOLD -> Triple("Walk", true, (speed / WALK_CLIP_SPEED).coerceIn(0.6f, 1.6f))
            a < PI / 4 -> Triple(if (aiming) "Run_Shoot" else "Run", true, (speed / RUN_CLIP_SPEED).coerceIn(0.7f, 1.5f))
            a > 3 * PI / 4 -> Triple("Run_Back", true, (speed / BACK_CLIP_SPEED).coerceIn(0.6f, 1.5f))
            moveAngle > 0 -> Triple("Run_Right", true, (speed / RUN_CLIP_SPEED).coerceIn(0.6f, 1.5f))
            else -> Triple("Run_Left", true, (speed / RUN_CLIP_SPEED).coerceIn(0.6f, 1.5f))
        }
    }

    companion object {
        private const val CROSSFADE = 0.2f
        const val RUN_THRESHOLD = 3.2f
        private const val CRAWL_CLIP_SPEED = 0.7f
        /** Ground speeds (m/s) at which each clip's feet don't slide at normal playback. */
        private const val WALK_CLIP_SPEED = 1.9f
        private const val RUN_CLIP_SPEED = 5.8f
        private const val BACK_CLIP_SPEED = 4.2f

        /** Direction of travel ([vx], [vz]) relative to [heading], using the game's yaw convention. */
        fun relativeAngle(vx: Float, vz: Float, heading: Float): Float {
            val travel = atan2(vx, -vz)
            var d = (travel - heading) % (2 * PI.toFloat())
            if (d > PI) d -= 2 * PI.toFloat()
            if (d < -PI) d += 2 * PI.toFloat()
            return d
        }
    }
}
