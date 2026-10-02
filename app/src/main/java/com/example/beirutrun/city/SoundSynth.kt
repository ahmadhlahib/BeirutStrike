package com.example.beirutrun.city

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Makes the game's sound effects from scratch, so the app needs no audio files:
 * a gunshot (noise crack plus a low thump) and short voice-like cries built with a simple formant
 * synthesizer (a buzzing "voice" shaped by three resonances, which is what makes a vowel sound).
 *
 * Output is mono 16-bit PCM at [SAMPLE_RATE], as samples in -1..1 or as a WAV file.
 */
object SoundSynth {
    const val SAMPLE_RATE = 22050

    /**
     * A shot: a noise crack with a falling low thump underneath, and for the big rifles an echo
     * off the buildings. [kind] sets the character: a pistol's short snap, a submachine gun's
     * muffled pop, a rifle's crack, a sniper rifle's long boom, a .50's deep blast.
     */
    fun gunshot(kind: ShotSound = ShotSound.RIFLE, seed: Int = 1): FloatArray {
        // Length (s), how fast the crack dies away (s), and how bright it is (0 dull .. 1 bright).
        val (length, crackDecay, bright) = when (kind) {
            ShotSound.PISTOL -> Triple(0.26f, 0.022f, 0.75f)
            ShotSound.MAGNUM -> Triple(0.45f, 0.032f, 0.6f)
            ShotSound.SMG -> Triple(0.2f, 0.016f, 0.35f)
            ShotSound.RIFLE -> Triple(0.38f, 0.035f, 0.55f)
            ShotSound.LMG -> Triple(0.42f, 0.038f, 0.5f)
            ShotSound.SNIPER -> Triple(1.0f, 0.05f, 0.6f)
            ShotSound.FIFTY -> Triple(1.3f, 0.06f, 0.45f)
        }
        // The thump: its lowest pitch and how far above it starts (Hz), and how fast it dies away (s).
        val (thumpBase, thumpSweep, thumpDecay) = when (kind) {
            ShotSound.PISTOL -> Triple(60.0, 90.0, 0.05f)
            ShotSound.MAGNUM -> Triple(45.0, 80.0, 0.09f)
            ShotSound.SMG -> Triple(70.0, 60.0, 0.035f)
            ShotSound.RIFLE -> Triple(40.0, 70.0, 0.09f)
            ShotSound.LMG -> Triple(38.0, 70.0, 0.1f)
            ShotSound.SNIPER -> Triple(34.0, 70.0, 0.17f)
            ShotSound.FIFTY -> Triple(26.0, 60.0, 0.26f)
        }
        val echo = kind == ShotSound.SNIPER || kind == ShotSound.FIFTY || kind == ShotSound.MAGNUM
        val n = (length * SAMPLE_RATE).toInt()
        val dry = FloatArray(n)
        val rnd = Random(seed)
        var lowpassed = 0f
        var thumpPhase = 0.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            // A one-pole low-pass gives the crack some body instead of pure hiss.
            lowpassed += (noise - lowpassed) * 0.45f
            val crack = (bright * noise + (1f - bright) * lowpassed) * exp(-t / crackDecay)
            val thumpFreq = thumpBase + thumpSweep * exp(-t / 0.05)
            thumpPhase += 2 * PI * thumpFreq / SAMPLE_RATE
            val thump = sin(thumpPhase).toFloat() * exp(-t / thumpDecay)
            val click = if (i < 40) (1f - i / 40f) else 0f
            dry[i] = crack * 0.9f + thump * 0.8f + click * 0.6f
        }
        if (!echo) return normalize(dry, 0.9f)
        // Two softer, duller copies arriving later, as if off the street's walls.
        val out = dry.copyOf()
        for ((delay, gain) in listOf(0.16f to 0.35f, 0.34f to 0.18f)) {
            val d = (delay * SAMPLE_RATE).toInt()
            var lp = 0f
            for (i in 0 until n - d) {
                lp += (dry[i] - lp) * 0.15f
                out[i + d] += lp * gain
            }
        }
        return normalize(out, 0.9f)
    }

    /**
     * Gun mechanics: a sharp metallic [ring] (Hz) over a short noise burst, with a little low
     * knock. Higher and shorter for small parts (a magazine release), lower and heavier for a slam.
     */
    private fun clack(ring: Float, seconds: Float, knock: Float, seed: Int): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        val body = Resonator()
        val bright = Resonator()
        var knockPhase = 0.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = (rnd.nextFloat() * 2f - 1f) * exp(-t / (seconds * 0.18f))
            val metal = body.process(noise, ring, 160f) * 3f + bright.process(noise, ring * 2.7f, 400f) * 1.5f
            knockPhase += 2 * PI * 120.0 / SAMPLE_RATE
            val low = sin(knockPhase).toFloat() * exp(-t / 0.025f) * knock
            out[i] = metal + low + noise * 0.25f
        }
        return normalize(out, 0.8f)
    }

    /** Sounds joined one after the other with [gap] seconds between their starts. */
    private fun sequence(gap: Float, vararg parts: FloatArray): FloatArray {
        val step = (gap * SAMPLE_RATE).toInt()
        val out = FloatArray(step * (parts.size - 1) + parts.last().size)
        parts.forEachIndexed { k, part -> for (i in part.indices) out[k * step + i] += part[i] }
        return normalize(out, 0.8f)
    }

    /** The empty magazine released and pulled out. */
    fun magazineOut() = sequence(0.07f, clack(2600f, 0.06f, 0.1f, 11), clack(1500f, 0.12f, 0.2f, 12))

    /** A full magazine pushed in until it locks. */
    fun magazineIn() = sequence(0.05f, clack(1300f, 0.08f, 0.6f, 13), clack(2100f, 0.1f, 0.4f, 14))

    /** Charging handle or slide pulled back and let go: a scrape and a snap forward. */
    fun rack() = sequence(0.11f, clack(1800f, 0.1f, 0.2f, 15), clack(1100f, 0.14f, 0.8f, 16))

    /** A bolt action worked: lifted and pulled back, pushed forward and turned down. */
    fun bolt() = sequence(0.16f, clack(1400f, 0.1f, 0.3f, 17), clack(1700f, 0.08f, 0.2f, 18), clack(1000f, 0.12f, 0.7f, 19))

    /** One round pressed into a rifle's magazine. */
    fun roundIn() = clack(2400f, 0.07f, 0.3f, 20)

    /** A machine gun's feed cover thrown open (or slammed shut, played lower). */
    fun feedCover() = clack(900f, 0.18f, 1f, 21)

    /** A new belt laid in the feed tray: a rattle of links. */
    fun beltRattle() = sequence(0.035f, *Array(7) { clack(3000f + it * 150f, 0.04f, 0.05f, 30 + it) })

    // ---- Grenades ---------------------------------------------------------------------------

    /** The pin pulled (a bright ping) and the spoon flying off as the grenade leaves the hand. */
    fun grenadePin() = sequence(0.12f, clack(3600f, 0.08f, 0f, 40), clack(2200f, 0.1f, 0.1f, 41))

    /**
     * A frag going off: a sharp crack and a deep rolling boom, its rumble dying away slowly, with
     * the echo coming back off the buildings.
     */
    fun explosion(seed: Int = 50): FloatArray {
        val n = (2.2f * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        var lp1 = 0f
        var lp2 = 0f
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            // Two low-passes: a dull rumble that lasts, and the brighter crack at the start.
            lp1 += (noise - lp1) * 0.035f
            lp2 += (noise - lp2) * 0.3f
            val rumble = lp1 * 9f * exp(-t / 0.55f)
            val crack = lp2 * exp(-t / 0.04f)
            phase += 2 * PI * (32.0 + 70.0 * exp(-t / 0.08)) / SAMPLE_RATE
            val thump = sin(phase).toFloat() * exp(-t / 0.3f)
            out[i] = rumble + crack * 1.2f + thump * 1.1f
        }
        for ((delay, gain) in listOf(0.22f to 0.3f, 0.5f to 0.15f)) {
            val d = (delay * SAMPLE_RATE).toInt()
            for (i in n - 1 downTo d) out[i] += out[i - d] * gain
        }
        return normalize(out, 0.95f)
    }

    /** A flashbang: one very sharp, bright bang, much shorter than a frag. */
    fun flashbang(seed: Int = 60): FloatArray {
        val n = (0.9f * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        var lp = 0f
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            lp += (noise - lp) * 0.12f
            phase += 2 * PI * (55.0 + 120.0 * exp(-t / 0.03)) / SAMPLE_RATE
            out[i] = noise * exp(-t / 0.05f) + lp * 2.5f * exp(-t / 0.25f) + sin(phase).toFloat() * exp(-t / 0.12f) * 0.8f
        }
        return normalize(out, 0.95f)
    }

    /** The ears ringing after a flashbang: a high whine, fading over a few seconds. */
    fun earRinging(): FloatArray {
        val seconds = 4f
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val wobble = 1f + 0.004f * sin(2 * PI * 3.0 * t).toFloat()
            val tone = sin(2 * PI * 3400.0 * wobble * t).toFloat() + 0.3f * sin(2 * PI * 6800.0 * t).toFloat()
            out[i] = tone * min(1f, t / 0.05f) * exp(-t / 1.4f)
        }
        return normalize(out, 0.5f)
    }

    /** A smoke grenade popping and hissing out its cloud. */
    fun smokeHiss(seed: Int = 70): FloatArray {
        val seconds = 3f
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        var lp = 0f
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            lp += (noise - lp) * 0.5f
            // A pop, then a hiss (the noise minus its low end) that swells and fades.
            val pop = if (t < 0.03f) noise * (1f - t / 0.03f) * 2f else 0f
            val hiss = (noise - lp) * min(1f, t / 0.15f) * min(1f, (seconds - t) / 1.2f)
            out[i] = pop + hiss * 0.6f
        }
        return normalize(out, 0.7f)
    }

    /** A molotov: the bottle smashing, then the fire catching with a whoosh. */
    fun molotov(seed: Int = 80): FloatArray {
        val glass = sequence(0.025f, *Array(6) { clack(3800f + it * 420f, 0.09f, 0.05f, seed + it) })
        val seconds = 1.6f
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed + 10)
        var lp = 0f
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            // The whoosh brightens as the flames rise, then settles to a crackle.
            lp += (noise - lp) * (0.04f + 0.2f * min(1f, t / 0.4f))
            val crackle = if (rnd.nextFloat() < 0.002f) 1.5f else 0f
            out[i] = lp * 3f * min(1f, t / 0.25f) * min(1f, (seconds - t) / 0.8f) + crackle * exp(-t / 1f)
            if (i < glass.size) out[i] += glass[i] * 0.8f
        }
        return normalize(out, 0.85f)
    }

    /** "Ay!": a quick cry gliding from an "ah" to an "ee" sound, pitch falling. */
    fun ouch(pitch: Float = 1f): FloatArray = voice(
        seconds = 0.34f,
        f0From = 270f * pitch, f0To = 190f * pitch,
        f1 = 780f to 330f, f2 = 1200f to 2250f, f3 = 2550f to 2950f,
        attack = 0.012f, release = 0.12f,
    )

    /** A longer groan for when a player is killed: "aaah-oh", sinking in pitch. */
    fun death(pitch: Float = 1f): FloatArray = voice(
        seconds = 0.85f,
        f0From = 215f * pitch, f0To = 105f * pitch,
        f1 = 720f to 460f, f2 = 1150f to 780f, f3 = 2500f to 2400f,
        attack = 0.03f, release = 0.45f,
    )

    /**
     * Formant voice: a band-limited buzz at a gliding pitch (the "vocal cords") fed through three
     * resonant filters whose frequencies glide from the first to the second value (the "mouth").
     */
    private fun voice(
        seconds: Float,
        f0From: Float, f0To: Float,
        f1: Pair<Float, Float>, f2: Pair<Float, Float>, f3: Pair<Float, Float>,
        attack: Float, release: Float,
    ): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val filters = arrayOf(Resonator(), Resonator(), Resonator())
        val gains = floatArrayOf(1f, 0.7f, 0.35f)
        val bandwidths = floatArrayOf(90f, 120f, 180f)
        val rnd = Random(7)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val k = t / seconds
            // A little vibrato and breath keep it from sounding like a pure machine tone.
            val f0 = lerp(f0From, f0To, k) * (1f + 0.02f * sin(2 * PI * 6.0 * t).toFloat())
            phase += f0 / SAMPLE_RATE
            if (phase >= 1.0) phase -= 1.0
            val source = glottalPulse(phase.toFloat()) + (rnd.nextFloat() - 0.5f) * 0.06f

            val formants = floatArrayOf(lerp(f1.first, f1.second, k), lerp(f2.first, f2.second, k), lerp(f3.first, f3.second, k))
            var sample = 0f
            for (j in 0..2) sample += gains[j] * filters[j].process(source, formants[j], bandwidths[j])

            val env = min(1f, t / attack) * min(1f, max(0f, (seconds - t) / release))
            out[i] = sample * env
        }
        return normalize(out, 0.85f)
    }

    /** One period of a vocal-fold-like pulse: a quick rise, a slower fall, then a closed phase. */
    private fun glottalPulse(p: Float): Float = when {
        p < 0.4f -> 0.5f - 0.5f * cos(PI * p / 0.4f).toFloat()
        p < 0.6f -> cos(PI / 2 * (p - 0.4f) / 0.2f).toFloat()
        else -> 0f
    } - 0.3f

    /** Two-pole resonant filter; its frequency may change every sample. */
    private class Resonator {
        private var y1 = 0f
        private var y2 = 0f

        fun process(x: Float, freq: Float, bandwidth: Float): Float {
            val r = exp(-PI * bandwidth / SAMPLE_RATE).toFloat()
            val b1 = 2f * r * cos(2 * PI * freq / SAMPLE_RATE).toFloat()
            val b2 = -r * r
            val y = (1f - r) * x + b1 * y1 + b2 * y2
            y2 = y1
            y1 = y
            return y
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun normalize(samples: FloatArray, peak: Float): FloatArray {
        val max = samples.maxOf { abs(it) }
        if (max > 0f) for (i in samples.indices) samples[i] = samples[i] / max * peak
        return samples
    }

    /** Wraps samples (-1..1) in a 16-bit mono WAV file. */
    fun toWav(samples: FloatArray): ByteArray {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }
        val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        return ByteArrayOutputStream(44 + dataSize).apply {
            write(header.array())
            write(data.array())
        }.toByteArray()
    }
}
