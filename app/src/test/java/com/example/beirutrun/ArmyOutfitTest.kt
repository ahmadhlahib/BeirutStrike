package com.example.beirutrun

import com.example.beirutrun.city.ArmyOutfit
import com.example.beirutrun.city.MaterialRole
import com.example.beirutrun.city.SoldierRig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Players' characters (assets/models/characters/) wear their clothes as army camouflage and
 * boots; their skin, face and eyes keep their own textures, and people in the street keep their
 * clothes. Writes the repainted clothes, in an olive team uniform, to build/army-outfit/ to look at.
 */
class ArmyOutfitTest {

    private val assets = File("src/main/assets")
    private val characters = File(assets, "models/characters").listFiles { f -> File(f, "character.glb").exists() }
        .orEmpty().sortedBy { it.name }

    private fun load(folder: String, army: Boolean) =
        SoldierRig.load(folder, army = army) { path -> File(assets, path).takeIf { it.exists() }?.readBytes() }

    @Test
    fun playersWearCamouflageAndBoots() {
        assumeTrue(characters.isNotEmpty())
        val out = File("build/army-outfit").apply { mkdirs() }
        for (dir in characters) {
            val rig = load("models/characters/${dir.name}", army = true)
            val materials = rig.model.primitives.map { it.material }.zip(rig.roles)
            println("${dir.name}: $materials")
            assertTrue("${dir.name}: no clothes in camouflage", MaterialRole.CAMO in rig.roles)
            for ((material, role) in materials) {
                if (material.startsWith("Avatar")) assertTrue("${dir.name}: $material is $role", role == MaterialRole.OWN)
            }
            // The repainted textures, coloured as drawn.
            rig.model.images.forEachIndexed { i, bytes ->
                val role = rig.imageRole(i)?.takeIf { it == MaterialRole.CAMO || it == MaterialRole.BOOTS } ?: return@forEachIndexed
                val (w, h, px) = readPixels(bytes) ?: return@forEachIndexed
                for (k in px.indices) px[k] = px[k] or 0xFF000000.toInt()
                ArmyOutfit.repaint(px, w, h, role)
                val tint = ArmyOutfit.color(role, OLIVE)
                for (k in px.indices) px[k] = multiply(px[k], tint)
                writePng(w, h, px, File(out, "${dir.name}_${i}_${role.name.lowercase()}.png"))
            }
            // Without army kit, the clothes stay as they are.
            val own = load("models/characters/${dir.name}", army = false)
            assertFalse(own.roles.any { it == MaterialRole.CAMO || it == MaterialRole.BOOTS })
        }
    }

    @Test
    fun streetPeopleKeepTheirClothes() {
        val folders = File(assets, "models/pedestrians").listFiles { f -> File(f, "character.glb").exists() }.orEmpty()
        assumeTrue(folders.isNotEmpty())
        for (f in folders) {
            val rig = SoldierRig.load("models/pedestrians/${f.name}") { path -> File(assets, path).takeIf { it.exists() }?.readBytes() }
            assertFalse("${f.name} in army kit", rig.roles.any { it == MaterialRole.CAMO || it == MaterialRole.BOOTS })
        }
    }

    // javax.imageio and java.awt are in the JVM the tests run on, but not in the Android API they
    // compile against, so they're reached by reflection (only to write the pictures).
    private val imageIO = Class.forName("javax.imageio.ImageIO")
    private val bufferedImage = Class.forName("java.awt.image.BufferedImage")

    /** Width, height and ARGB pixels of a JPEG or PNG, or null if it doesn't read. */
    private fun readPixels(bytes: ByteArray): Triple<Int, Int, IntArray>? {
        val image = imageIO.getMethod("read", java.io.InputStream::class.java).invoke(null, bytes.inputStream()) ?: return null
        val w = bufferedImage.getMethod("getWidth").invoke(image) as Int
        val h = bufferedImage.getMethod("getHeight").invoke(image) as Int
        val get = bufferedImage.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
        return Triple(w, h, get.invoke(image, 0, 0, w, h, null, 0, w) as IntArray)
    }

    private fun writePng(w: Int, h: Int, px: IntArray, file: File) {
        val image = bufferedImage.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(w, h, 1) // TYPE_INT_RGB
        bufferedImage.getMethod("setRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(image, 0, 0, w, h, px, 0, w)
        val rendered = Class.forName("java.awt.image.RenderedImage")
        imageIO.getMethod("write", rendered, String::class.java, File::class.java).invoke(null, image, "png", file)
    }

    private fun multiply(a: Int, b: Int): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (b shr s and 0xFF)) / 255
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        /** An olive team uniform (as in Teams). */
        const val OLIVE = 0xFF5B5A2E.toInt()
    }
}
