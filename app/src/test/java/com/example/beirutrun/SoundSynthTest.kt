package com.example.beirutrun

import com.example.beirutrun.city.SoundSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SoundSynthTest {

    private fun check(samples: FloatArray, seconds: Float) {
        assertEquals(seconds, samples.size / SoundSynth.SAMPLE_RATE.toFloat(), 0.01f)
        val peak = samples.maxOf { abs(it) }
        assertTrue("too quiet: $peak", peak > 0.5f)
        assertTrue("clips: $peak", peak <= 1f)
        assertTrue("contains NaN", samples.none { it.isNaN() })
    }

    @Test
    fun soundsHaveExpectedLengthAndLevel() {
        check(SoundSynth.gunshot(), 0.38f)
        check(SoundSynth.ouch(), 0.34f)
        check(SoundSynth.death(), 0.85f)
    }

    @Test
    fun gunshotIsLoudAtStartAndFadesOut() {
        val s = SoundSynth.gunshot()
        val n = SoundSynth.SAMPLE_RATE / 20
        val start = s.take(n).maxOf { abs(it) }
        val end = s.takeLast(n).maxOf { abs(it) }
        assertTrue("start $start should be much louder than end $end", start > end * 5)
    }

    @Test
    fun wavHeaderDescribesMono16BitPcm() {
        val samples = SoundSynth.ouch()
        val wav = SoundSynth.toWav(samples)
        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, header.getShort(20).toInt())                // PCM
        assertEquals(1, header.getShort(22).toInt())                // mono
        assertEquals(SoundSynth.SAMPLE_RATE, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())               // bits per sample
        assertEquals(samples.size * 2, header.getInt(40))
    }
}
