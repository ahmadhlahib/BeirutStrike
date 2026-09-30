package com.example.beirutrun.city

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A detailed 3D gun model (see [GunMeshes]), in the same gun space as [GunModel]: pointing along
 * -z, y up, metres, with the grip where the hands hold it.
 *
 * @property textures Each texture's image file (PNG bytes), by index.
 * @property supportY How high the left hand holds the handguard.
 */
class GunMesh(
    val parts: List<Part>,
    val textures: List<ByteArray>,
    val gripY: Float, val gripZ: Float,
    val supportZ: Float, val supportY: Float,
    val muzzleY: Float, val muzzleZ: Float,
) {
    /** One colour or texture's triangles: 8 floats per vertex (x y z, normal x y z, u v). */
    class Part(val color: Int, val texture: Int, val vertices: FloatArray, val indices: ShortArray)
}

/**
 * The detailed gun models in `assets/guns3d/<gun>.gun` (named after the [Weapon], e.g.
 * `ak47.gun`), made from the Sketchfab models by tools/GunModelTool.java; credits in
 * `assets/guns3d/CREDITS.txt`. A gun without one is drawn from boxes ([GunModels]).
 */
object GunMeshes {
    /**
     * The first-person arms, posed round a grip (see tools/GunModelTool.java "arms"): each piece's
     * palm contact point is at the origin, and its forearm triangles (alpha 0) take the team's
     * sleeve colour.
     */
    enum class Arm(val file: String) { RIFLE_RIGHT("arms_rifle_r"), RIFLE_LEFT("arms_rifle_l"), PISTOL_RIGHT("arms_pistol_r"), PISTOL_LEFT("arms_pistol_l") }

    /** Every gun model and arm piece there is. */
    class Library(val guns: Map<Weapon, GunMesh>, val arms: Map<Arm, GunMesh>)

    /** Reads every gun's model and the arms; [open] reads an asset's bytes, or returns null if it's missing. Slow: call it off the main thread. */
    fun loadAll(open: (String) -> ByteArray?): Library = Library(
        Weapon.entries.mapNotNull { w -> open("guns3d/${w.name.lowercase()}.gun")?.let { w to parse(it, open) } }.toMap(),
        Arm.entries.mapNotNull { a -> open("guns3d/${a.file}.gun")?.let { a to parse(it, open) } }.toMap(),
    )

    /** Reads a .gun file (format in tools/GunModelTool.java). */
    fun parse(bytes: ByteArray, open: (String) -> ByteArray?): GunMesh {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic) == "GUN1") { "Not a .gun file" }
        val gripY = b.float; val gripZ = b.float; val supportZ = b.float; val muzzleY = b.float; val muzzleZ = b.float
        val textures = List(b.int) {
            val name = ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }
            open("guns3d/${String(name, Charsets.UTF_8)}") ?: ByteArray(0)
        }
        val parts = List(b.int) {
            val color = b.int
            val texture = b.int
            val vertices = FloatArray(b.int * 8).also { b.asFloatBuffer().get(it); b.position(b.position() + it.size * 4) }
            val indices = ShortArray(b.int).also { b.asShortBuffer().get(it); b.position(b.position() + it.size * 2) }
            GunMesh.Part(color, texture, vertices, indices)
        }
        // The left hand holds the handguard a little below the barrel.
        return GunMesh(parts, textures, gripY, gripZ, supportZ, muzzleY - 0.035f, muzzleY, muzzleZ)
    }
}
