package com.example.beirutrun

import com.example.beirutrun.city.GunMeshes
import com.example.beirutrun.city.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The packed 3D gun models in assets/guns3d/ (see tools/GunModelTool.java). */
class GunMeshTest {

    private val assets = File("src/main/assets")
    private val library = GunMeshes.loadAll { path -> File(assets, path).takeIf { it.exists() }?.readBytes() }
    private val all = library.guns

    @Test
    fun everyGunHasAModel() {
        assertEquals(Weapon.entries.toSet(), all.keys)
    }

    @Test
    fun allFourArmsAreThereWithASleeve() {
        assertEquals(GunMeshes.Arm.entries.toSet(), library.arms.keys)
        for ((arm, mesh) in library.arms) {
            // A skin part and a sleeve part (no alpha: drawn in the team's colour).
            assertTrue("$arm: sleeve", mesh.parts.any { it.color ushr 24 == 0 })
            assertTrue("$arm: skin", mesh.parts.any { it.color ushr 24 == 0xFF })
        }
    }

    @Test
    fun modelsAreValidAndTheMuzzleIsAheadOfTheGrip() {
        for ((gun, mesh) in all) {
            val who = gun.displayName
            assertTrue("$who: no parts", mesh.parts.isNotEmpty())
            for (p in mesh.parts) {
                val vertices = p.vertices.size / 8
                assertTrue("$who: vertices", vertices in 1..65535)
                assertTrue("$who: triangles", p.indices.size % 3 == 0 && p.indices.isNotEmpty())
                assertTrue("$who: index out of range", p.indices.all { (it.toInt() and 0xFFFF) < vertices })
                assertTrue("$who: texture", p.texture < mesh.textures.size)
                assertTrue("$who: finite", p.vertices.all { it.isFinite() })
            }
            assertTrue("$who: muzzle should be ahead of the grip", mesh.muzzleZ < mesh.gripZ)
            // The model's length matches the real gun's (within a few centimetres).
            var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (p in mesh.parts) for (i in p.vertices.indices step 8) { minZ = minOf(minZ, p.vertices[i + 2]); maxZ = maxOf(maxZ, p.vertices[i + 2]) }
            assertTrue("$who: ${maxZ - minZ} m long", maxZ - minZ in 0.15f..1.6f)
        }
    }
}
