package com.example.beirutrun

import com.example.beirutrun.city.SkinnedModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Prints how far each Mixamo clip moves the hips (root motion), to spot clips that aren't "In Place". */
class MixamoClipReport {
    @Test
    fun report() {
        val dir = File("src/main/assets/models/mixamo")
        assumeTrue(dir.exists())
        for (f in dir.listFiles { x -> x.extension == "glb" && x.name != "character.glb" }.orEmpty().sortedBy { it.name }) {
            val m = SkinnedModel.load(f.readBytes())
            val clip = m.clips.values.firstOrNull() ?: continue
            val hips = m.nodeIndex("mixamorig:Hips")
            val ch = clip.channels.firstOrNull { it.node == hips && it.path == 0 }
            if (ch == null) { println("${f.name}: ${clip.duration}s, no hips translation"); continue }
            val n = ch.times.size
            val first = FloatArray(3) { ch.values[it] }
            val last = FloatArray(3) { ch.values[(n - 1) * 3 + it] }
            val min = FloatArray(3) { k -> (0 until n).minOf { ch.values[it * 3 + k] } }
            val max = FloatArray(3) { k -> (0 until n).maxOf { ch.values[it * 3 + k] } }
            println("%-18s %.2fs  hips start (%.1f %.1f %.1f) end (%.1f %.1f %.1f) range x %.1f y %.1f z %.1f".format(
                f.name, clip.duration, first[0], first[1], first[2], last[0], last[1], last[2],
                max[0] - min[0], max[1] - min[1], max[2] - min[2]))
        }
    }
}
