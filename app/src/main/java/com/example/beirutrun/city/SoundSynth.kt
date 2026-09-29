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

    /** A short, sharp shot: bright noise crack with a falling low thump underneath. */
    fun gunshot(seed: Int = 1): FloatArray {
        val n = (0.38f * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed)
        var lowpassed = 0f
        var thumpPhase = 0.0
        for (i in 0 until n) {
            val t = i / SAMPLE_RATE.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            // A one-pole low-pass gives the crack some body instead of pure hiss.
            lowpassed += (noise - lowpassed) * 0.45f
            val crack = (0.55f * noise + 0.45f * lowpassed) * exp(-t / 0.035f)
            val thumpFreq = 40.0 + 70.0 * exp(-t / 0.05)
            thumpPhase += 2 * PI * thumpFreq / SAMPLE_RATE
            val thump = sin(thumpPhase).toFloat() * exp(-t / 0.09f)
            val click = if (i < 40) (1f - i / 40f) else 0f
            out[i] = crack * 0.9f + thump * 0.8f + click * 0.6f
        }
        return normalize(out, 0.9f)
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
