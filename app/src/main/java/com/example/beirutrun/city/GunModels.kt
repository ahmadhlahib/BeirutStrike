package com.example.beirutrun.city

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * One box of a gun, in metres, in the gun's own space: pointing along -z, y up, x to the right,
 * the receiver (or a pistol's frame) at the origin. [pitch] tips the box's front up, degrees.
 */
class GunPart(
    val x: Float, val y: Float, val z: Float,
    val sx: Float, val sy: Float, val sz: Float,
    val color: Int, val pitch: Float = 0f,
)

/**
 * A gun's shape, built from boxes. The same parts are drawn in first person (see CityRenderer),
 * on other soldiers, and as a picture on the loadout screen ([GunIcon]).
 *
 * @property gripY Where the right hand holds the pistol grip.
 * @property gripZ The same, along the gun.
 * @property supportZ Where the left hand holds the handguard (rifles).
 * @property muzzleY Where bullets and the flash leave the barrel.
 * @property muzzleZ The same, along the gun.
 */
class GunModel(
    val parts: List<GunPart>,
    val gripY: Float, val gripZ: Float,
    val supportZ: Float,
    val muzzleY: Float, val muzzleZ: Float,
)

/** The shape of every [Weapon], after the real guns: proportions, magazines, stocks, scopes. */
object GunModels {
    private const val STEEL = 0xFF2B2D2F.toInt()
    private const val DARK_STEEL = 0xFF1E2022.toInt()
    private const val BLACK = 0xFF151515.toInt()
    private const val POLYMER = 0xFF1C1D1F.toInt()
    private const val COVER = 0xFF383B3E.toInt()
    private const val WOOD = 0xFF8A5530.toInt()
    private const val DARK_WOOD = 0xFF6B3F22.toInt()
    private const val BAKELITE = 0xFF8C3A14.toInt()
    private const val STAINLESS = 0xFF9EA3A8.toInt()
    private const val STAINLESS_DARK = 0xFF7B8085.toInt()
    private const val OD_GREEN = 0xFF4A5A3A.toInt()
    private const val OD_DARK = 0xFF3A4430.toInt()
    private const val TAN = 0xFF8C7A5B.toInt()
    private const val PARKERIZED = 0xFF34363A.toInt()
    private const val LENS = 0xFF3A6EA5.toInt()

    private fun p(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, color: Int, pitch: Float = 0f) =
        GunPart(x, y, z, sx, sy, sz, color, pitch)

    private val models = HashMap<Weapon, GunModel>()

    fun of(weapon: Weapon): GunModel = models.getOrPut(weapon) {
        when (weapon) {
            Weapon.M9 -> m9()
            Weapon.GLOCK17 -> glock()
            Weapon.DEAGLE -> deagle()
            Weapon.AK47 -> ak47()
            Weapon.M4 -> m4()
            Weapon.MP5 -> mp5()
            Weapon.RPK -> rpk()
            Weapon.M249 -> m249()
            Weapon.SVD -> svd()
            Weapon.M24 -> m24()
            Weapon.AWM -> awm()
            Weapon.M82 -> m82()
        }
    }

    /** A rifle scope on top of the receiver: tube, rings, bells and turrets; [length] over the tube. */
    private fun scope(y: Float, z: Float, length: Float, tube: Float, body: Int = BLACK): List<GunPart> = listOf(
        p(0f, y, z, tube, tube, length, body),                                  // tube
        p(0f, y + 0.004f, z - length / 2f - 0.02f, tube * 1.45f, tube * 1.45f, 0.06f, body), // objective bell
        p(0f, y + 0.004f, z - length / 2f - 0.051f, tube * 1.2f, tube * 1.2f, 0.004f, LENS),
        p(0f, y, z + length / 2f + 0.02f, tube * 1.25f, tube * 1.25f, 0.05f, body),          // eyepiece
        p(0f, y + tube * 0.75f, z, tube * 0.55f, tube * 0.5f, tube * 0.55f, body),          // elevation turret
        p(tube * 0.75f, y, z, tube * 0.5f, tube * 0.55f, tube * 0.55f, body),               // windage turret
        p(0f, y - tube * 0.75f, z - length * 0.3f, tube * 0.5f, tube * 0.8f, 0.018f, DARK_STEEL), // front ring
        p(0f, y - tube * 0.75f, z + length * 0.3f, tube * 0.5f, tube * 0.8f, 0.018f, DARK_STEEL), // rear ring
    )

    // ---- Pistols ------------------------------------------------------------------------------

    /** Beretta M9: long slide with the barrel showing through its open top, black grips. */
    private fun m9() = GunModel(
        listOf(
            p(0f, 0.022f, -0.075f, 0.029f, 0.03f, 0.2f, STEEL),                 // slide
            p(0f, 0.037f, -0.13f, 0.016f, 0.01f, 0.09f, STAINLESS_DARK),        // barrel in the open top
            p(0f, 0.022f, -0.177f, 0.014f, 0.014f, 0.004f, BLACK),              // barrel opening
            p(0.016f, 0.024f, 0.005f, 0.004f, 0.012f, 0.022f, BLACK),           // safety/decocker
            p(0f, -0.004f, -0.065f, 0.027f, 0.02f, 0.17f, PARKERIZED),          // frame
            p(0f, 0.042f, 0.02f, 0.024f, 0.01f, 0.01f, BLACK),                  // rear sight
            p(0f, 0.042f, -0.162f, 0.006f, 0.01f, 0.008f, BLACK),               // front sight
            p(0f, -0.029f, -0.045f, 0.01f, 0.008f, 0.052f, PARKERIZED),         // trigger guard
            p(0f, -0.022f, -0.035f, 0.005f, 0.018f, 0.005f, BLACK),             // trigger
            p(0f, -0.06f, 0.015f, 0.031f, 0.1f, 0.047f, POLYMER, -16f),         // grip
            p(0f, -0.11f, 0.03f, 0.028f, 0.012f, 0.04f, BLACK, -16f),           // magazine base
        ),
        gripY = -0.06f, gripZ = 0.015f, supportZ = 0f, muzzleY = 0.022f, muzzleZ = -0.18f,
    )

    /** Glock 17: squared-off slide, all-black polymer frame with a steep grip. */
    private fun glock() = GunModel(
        listOf(
            p(0f, 0.023f, -0.07f, 0.03f, 0.033f, 0.186f, DARK_STEEL),           // slide
            p(0f, 0.023f, 0.005f, 0.031f, 0.027f, 0.03f, BLACK),                // slide serrations
            p(0f, 0.023f, -0.164f, 0.013f, 0.013f, 0.004f, BLACK),              // barrel opening
            p(0f, -0.004f, -0.062f, 0.029f, 0.02f, 0.162f, POLYMER),            // frame
            p(0f, -0.012f, -0.13f, 0.02f, 0.006f, 0.035f, POLYMER),             // accessory rail
            p(0f, 0.044f, 0.017f, 0.024f, 0.01f, 0.01f, BLACK),                 // rear sight
            p(0f, 0.044f, -0.152f, 0.006f, 0.01f, 0.008f, 0xFFE0E0E0.toInt()),  // white front sight
            p(0f, -0.03f, -0.045f, 0.011f, 0.009f, 0.05f, POLYMER),             // trigger guard
            p(0f, -0.022f, -0.035f, 0.006f, 0.018f, 0.005f, BLACK),             // trigger
            p(0f, -0.06f, 0.013f, 0.032f, 0.1f, 0.046f, POLYMER, -22f),         // grip
            p(0f, -0.108f, 0.033f, 0.03f, 0.01f, 0.042f, BLACK, -22f),          // magazine base
        ),
        gripY = -0.06f, gripZ = 0.013f, supportZ = 0f, muzzleY = 0.023f, muzzleZ = -0.166f,
    )

    /** Desert Eagle: big stainless slide with the triangular barrel and rail on top, chunky grip. */
    private fun deagle() = GunModel(
        listOf(
            p(0f, 0.025f, -0.035f, 0.034f, 0.036f, 0.13f, STAINLESS),           // slide
            p(0f, 0.031f, -0.16f, 0.03f, 0.03f, 0.13f, STAINLESS),              // fixed barrel
            p(0f, 0.051f, -0.13f, 0.012f, 0.012f, 0.18f, STAINLESS_DARK),       // top rail
            p(0f, 0.031f, -0.227f, 0.02f, 0.02f, 0.004f, BLACK),                // barrel opening
            p(0f, 0.025f, 0.015f, 0.035f, 0.03f, 0.03f, STAINLESS_DARK),        // slide serrations
            p(0f, -0.006f, -0.075f, 0.032f, 0.022f, 0.2f, STAINLESS_DARK),      // frame
            p(0f, 0.058f, 0.02f, 0.026f, 0.012f, 0.012f, BLACK),                // rear sight
            p(0f, 0.058f, -0.21f, 0.007f, 0.012f, 0.009f, BLACK),               // front sight
            p(0f, -0.032f, -0.05f, 0.012f, 0.009f, 0.058f, STAINLESS_DARK),     // trigger guard
            p(0f, -0.025f, -0.04f, 0.006f, 0.02f, 0.006f, BLACK),               // trigger
            p(0f, -0.065f, 0.018f, 0.036f, 0.11f, 0.055f, BLACK, -14f),         // grip
        ),
        gripY = -0.065f, gripZ = 0.018f, supportZ = 0f, muzzleY = 0.031f, muzzleZ = -0.23f,
    )

    // ---- Primaries ----------------------------------------------------------------------------

    /** AK-47: steel receiver and dust cover, wood furniture, curved bakelite magazine, muzzle brake. */
    private fun ak47() = GunModel(
        listOf(
            p(0f, 0f, 0f, 0.05f, 0.07f, 0.3f, STEEL),                            // receiver
            p(0f, 0.042f, 0.015f, 0.046f, 0.018f, 0.27f, COVER),                // dust cover
            p(0.027f, 0.012f, 0f, 0.004f, 0.012f, 0.15f, STEEL),                // selector lever
            p(0f, 0.052f, -0.15f, 0.03f, 0.03f, 0.05f, STEEL),                  // rear sight block
            p(0f, -0.004f, -0.27f, 0.058f, 0.058f, 0.2f, WOOD),                 // lower handguard
            p(0f, 0.044f, -0.26f, 0.04f, 0.028f, 0.18f, DARK_WOOD),             // upper handguard
            p(0f, 0.04f, -0.39f, 0.03f, 0.036f, 0.03f, STEEL),                  // gas block
            p(0f, 0.04f, -0.44f, 0.016f, 0.016f, 0.08f, STEEL),                 // gas tube tip
            p(0f, 0.004f, -0.48f, 0.022f, 0.022f, 0.3f, STEEL),                 // barrel
            p(0f, 0.03f, -0.56f, 0.026f, 0.05f, 0.026f, STEEL),                 // front sight base
            p(0f, 0.065f, -0.56f, 0.007f, 0.03f, 0.007f, STEEL),                // front sight post
            p(0f, 0.004f, -0.64f, 0.03f, 0.03f, 0.06f, BLACK),                  // muzzle brake
            p(0f, -0.075f, -0.07f, 0.034f, 0.07f, 0.075f, BAKELITE, 8f),        // curved magazine
            p(0f, -0.135f, -0.086f, 0.034f, 0.065f, 0.075f, BAKELITE, 20f),
            p(0f, -0.188f, -0.112f, 0.034f, 0.06f, 0.074f, BAKELITE, 33f),
            p(0f, -0.047f, 0.04f, 0.012f, 0.01f, 0.07f, STEEL),                 // trigger guard
            p(0f, -0.038f, 0.035f, 0.006f, 0.022f, 0.006f, BLACK),              // trigger
            p(0f, -0.085f, 0.1f, 0.034f, 0.1f, 0.045f, DARK_WOOD, -18f),        // pistol grip
            p(0f, -0.03f, 0.3f, 0.044f, 0.075f, 0.3f, WOOD, 6f),               // stock, dropping to the rear
        ),
        gripY = -0.085f, gripZ = 0.1f, supportZ = -0.28f, muzzleY = 0.004f, muzzleZ = -0.67f,
    )

    /** M4A1 with a 4× ACOG: black flat-top receiver, rail handguard, collapsible stock, straight magazine. */
    private fun m4() = GunModel(
        listOf(
            p(0f, 0.012f, -0.01f, 0.046f, 0.05f, 0.25f, POLYMER),                // upper receiver
            p(0f, -0.028f, 0.03f, 0.044f, 0.04f, 0.17f, POLYMER),                // lower receiver
            p(0f, 0.042f, -0.02f, 0.024f, 0.012f, 0.24f, BLACK),                // flat-top rail
            p(0.026f, 0.018f, 0.04f, 0.01f, 0.018f, 0.04f, BLACK),              // ejection port cover
            p(0f, 0.006f, -0.24f, 0.058f, 0.058f, 0.2f, BLACK),                 // quad-rail handguard
            p(0f, 0.04f, -0.24f, 0.022f, 0.01f, 0.2f, DARK_STEEL),              // top rail
            p(0f, 0.035f, -0.365f, 0.016f, 0.06f, 0.02f, BLACK),                // A-frame front sight
            p(0f, 0.006f, -0.44f, 0.018f, 0.018f, 0.2f, DARK_STEEL),            // barrel
            p(0f, 0.006f, -0.555f, 0.024f, 0.024f, 0.05f, BLACK),               // flash hider
            p(0f, -0.09f, -0.06f, 0.028f, 0.12f, 0.07f, POLYMER, 6f),           // straight magazine
            p(0f, -0.046f, 0.06f, 0.012f, 0.008f, 0.07f, POLYMER),              // trigger guard
            p(0f, -0.038f, 0.055f, 0.006f, 0.02f, 0.006f, BLACK),               // trigger
            p(0f, -0.09f, 0.12f, 0.032f, 0.1f, 0.042f, POLYMER, -20f),          // pistol grip
            p(0f, 0.008f, 0.24f, 0.028f, 0.028f, 0.2f, BLACK),                  // buffer tube
            p(0f, -0.006f, 0.33f, 0.042f, 0.075f, 0.13f, POLYMER),              // collapsible stock
            p(0f, 0.08f, -0.03f, 0.05f, 0.05f, 0.14f, 0xFF2A2B26.toInt()),      // ACOG body
            p(0f, 0.084f, -0.105f, 0.055f, 0.055f, 0.02f, 0xFF2A2B26.toInt()),  // ACOG objective
            p(0f, 0.084f, -0.116f, 0.044f, 0.044f, 0.004f, LENS),
            p(0f, 0.106f, -0.03f, 0.014f, 0.012f, 0.03f, 0xFFC0CA33.toInt()),   // fibre-optic
            p(0f, 0.052f, -0.03f, 0.03f, 0.014f, 0.06f, BLACK),                 // ACOG mount
        ),
        gripY = -0.09f, gripZ = 0.12f, supportZ = -0.25f, muzzleY = 0.006f, muzzleZ = -0.585f,
    )

    /** MP5: compact tubular receiver, curved 9 mm magazine, drum rear sight, hooded front sight, fixed stock. */
    private fun mp5() = GunModel(
        listOf(
            p(0f, 0.012f, -0.02f, 0.046f, 0.058f, 0.3f, PARKERIZED),             // receiver
            p(0f, 0.045f, -0.2f, 0.02f, 0.02f, 0.14f, PARKERIZED),               // cocking tube
            p(-0.02f, 0.045f, -0.19f, 0.024f, 0.008f, 0.012f, BLACK),            // cocking handle
            p(0f, 0.054f, 0.12f, 0.03f, 0.03f, 0.03f, BLACK),                   // drum rear sight
            p(0f, 0.058f, -0.315f, 0.03f, 0.03f, 0.02f, BLACK),                 // front sight hood
            p(0f, -0.004f, -0.225f, 0.06f, 0.062f, 0.13f, POLYMER),             // tropical handguard
            p(0f, 0.012f, -0.34f, 0.018f, 0.018f, 0.06f, DARK_STEEL),           // barrel
            p(0f, 0.012f, -0.378f, 0.024f, 0.024f, 0.02f, BLACK),               // barrel lugs
            p(0f, -0.07f, -0.1f, 0.026f, 0.07f, 0.05f, PARKERIZED, 12f),        // curved magazine
            p(0f, -0.128f, -0.116f, 0.026f, 0.065f, 0.05f, PARKERIZED, 24f),
            p(0f, -0.045f, 0.05f, 0.012f, 0.009f, 0.08f, POLYMER),              // trigger group
            p(0f, -0.038f, 0.04f, 0.006f, 0.02f, 0.006f, BLACK),                // trigger
            p(0f, -0.085f, 0.09f, 0.034f, 0.09f, 0.045f, POLYMER, -16f),        // pistol grip
            p(0f, 0f, 0.29f, 0.044f, 0.07f, 0.26f, POLYMER, -3f),               // fixed stock
        ),
        gripY = -0.085f, gripZ = 0.09f, supportZ = -0.22f, muzzleY = 0.012f, muzzleZ = -0.39f,
    )

    /** RPK: an AK stretched into a light machine gun: long heavy barrel, bipod, club-foot stock, 40-round magazine. */
    private fun rpk() = GunModel(
        listOf(
            p(0f, 0f, 0f, 0.052f, 0.072f, 0.32f, STEEL),                         // receiver
            p(0f, 0.044f, 0.015f, 0.048f, 0.018f, 0.29f, COVER),                // dust cover
            p(0f, 0.054f, -0.16f, 0.032f, 0.03f, 0.05f, STEEL),                 // rear sight block
            p(0f, -0.004f, -0.28f, 0.058f, 0.058f, 0.2f, WOOD),                 // lower handguard
            p(0f, 0.044f, -0.27f, 0.04f, 0.028f, 0.18f, DARK_WOOD),             // upper handguard
            p(0f, 0.04f, -0.42f, 0.03f, 0.036f, 0.03f, STEEL),                  // gas block
            p(0f, 0.004f, -0.6f, 0.026f, 0.026f, 0.5f, STEEL),                  // heavy barrel
            p(0f, 0.03f, -0.8f, 0.026f, 0.05f, 0.026f, STEEL),                  // front sight
            p(0f, 0.004f, -0.86f, 0.03f, 0.03f, 0.04f, BLACK),                  // muzzle
            p(0.012f, -0.03f, -0.62f, 0.008f, 0.008f, 0.26f, STEEL),            // folded bipod legs
            p(-0.012f, -0.03f, -0.62f, 0.008f, 0.008f, 0.26f, STEEL),
            p(0f, -0.08f, -0.075f, 0.035f, 0.08f, 0.08f, BAKELITE, 8f),         // long curved magazine
            p(0f, -0.15f, -0.092f, 0.035f, 0.075f, 0.08f, BAKELITE, 18f),
            p(0f, -0.213f, -0.118f, 0.035f, 0.07f, 0.079f, BAKELITE, 28f),
            p(0f, -0.268f, -0.152f, 0.035f, 0.06f, 0.078f, BAKELITE, 38f),
            p(0f, -0.047f, 0.04f, 0.012f, 0.01f, 0.07f, STEEL),                 // trigger guard
            p(0f, -0.085f, 0.1f, 0.034f, 0.1f, 0.045f, DARK_WOOD, -18f),        // pistol grip
            p(0f, -0.04f, 0.31f, 0.046f, 0.1f, 0.32f, WOOD, 6f),                // club-foot stock
        ),
        gripY = -0.085f, gripZ = 0.1f, supportZ = -0.3f, muzzleY = 0.004f, muzzleZ = -0.88f,
    )

    /** M249 SAW: boxy receiver with a feed cover, 100-round pouch under it, carry handle, bipod, skeleton stock. */
    private fun m249() = GunModel(
        listOf(
            p(0f, 0.004f, 0f, 0.07f, 0.085f, 0.34f, PARKERIZED),                 // receiver
            p(0f, 0.056f, -0.02f, 0.066f, 0.022f, 0.2f, POLYMER),               // feed cover
            p(0f, 0.07f, 0.11f, 0.03f, 0.02f, 0.05f, BLACK),                    // rear sight
            p(-0.028f, -0.1f, -0.03f, 0.1f, 0.12f, 0.13f, OD_GREEN),            // 100-round pouch
            p(-0.028f, -0.04f, -0.03f, 0.1f, 0.012f, 0.13f, OD_DARK),           // pouch flap
            p(0f, -0.004f, -0.26f, 0.064f, 0.06f, 0.18f, POLYMER),              // handguard
            p(0f, 0.004f, -0.52f, 0.026f, 0.026f, 0.36f, DARK_STEEL),           // barrel
            p(0f, 0.035f, -0.4f, 0.03f, 0.018f, 0.12f, BLACK),                  // heat shield
            p(0f, 0.075f, -0.36f, 0.018f, 0.04f, 0.018f, BLACK),                // carry handle post
            p(0f, 0.095f, -0.32f, 0.018f, 0.014f, 0.1f, BLACK),                 // carry handle
            p(0f, 0.05f, -0.66f, 0.014f, 0.05f, 0.014f, BLACK),                 // front sight
            p(0f, 0.004f, -0.72f, 0.03f, 0.03f, 0.05f, BLACK),                  // flash hider
            p(0.014f, -0.03f, -0.56f, 0.009f, 0.009f, 0.24f, BLACK),            // folded bipod
            p(-0.014f, -0.03f, -0.56f, 0.009f, 0.009f, 0.24f, BLACK),
            p(0f, -0.05f, 0.08f, 0.014f, 0.012f, 0.08f, PARKERIZED),            // trigger guard
            p(0f, -0.1f, 0.13f, 0.036f, 0.1f, 0.046f, POLYMER, -18f),           // pistol grip
            p(0f, 0f, 0.3f, 0.05f, 0.085f, 0.26f, POLYMER, -3f),                // skeleton stock
        ),
        gripY = -0.1f, gripZ = 0.13f, supportZ = -0.28f, muzzleY = 0.004f, muzzleZ = -0.75f,
    )

    // ---- Sniper rifles ------------------------------------------------------------------------

    /** SVD Dragunov: slim receiver, skeletonised wooden thumbhole stock, long barrel, PSO-1 scope. */
    private fun svd() = GunModel(
        listOf(
            p(0f, 0f, 0f, 0.046f, 0.065f, 0.3f, STEEL),                          // receiver
            p(0f, 0.04f, 0.02f, 0.044f, 0.016f, 0.26f, COVER),                  // dust cover
            p(0f, -0.004f, -0.28f, 0.052f, 0.055f, 0.24f, WOOD),                // handguard
            p(0f, 0.004f, -0.66f, 0.02f, 0.02f, 0.56f, STEEL),                  // long barrel
            p(0f, 0.03f, -0.85f, 0.02f, 0.04f, 0.02f, STEEL),                   // front sight
            p(0f, 0.004f, -0.96f, 0.028f, 0.028f, 0.07f, BLACK),                // flash hider
            p(0f, -0.07f, -0.06f, 0.03f, 0.08f, 0.07f, BAKELITE, 6f),           // 10-round magazine
            p(0f, -0.045f, 0.05f, 0.012f, 0.01f, 0.07f, STEEL),                 // trigger guard
            p(0f, -0.085f, 0.13f, 0.034f, 0.1f, 0.04f, WOOD, -20f),             // thumbhole grip
            p(0f, 0.01f, 0.24f, 0.04f, 0.03f, 0.2f, WOOD),                      // stock comb
            p(0f, -0.03f, 0.37f, 0.042f, 0.12f, 0.06f, WOOD),                   // butt
            p(0f, -0.075f, 0.26f, 0.034f, 0.02f, 0.2f, DARK_WOOD, 8f),          // lower stock bar
            p(0f, 0.05f, 0.29f, 0.036f, 0.022f, 0.08f, DARK_WOOD),              // cheek rest
        ) + scope(y = 0.085f, z = -0.01f, length = 0.2f, tube = 0.036f),
        gripY = -0.085f, gripZ = 0.13f, supportZ = -0.3f, muzzleY = 0.004f, muzzleZ = -1.0f,
    )

    /** M24: full-length olive synthetic stock, heavy barrel, bolt handle, long 10× scope. */
    private fun m24() = GunModel(
        listOf(
            p(0f, 0.012f, 0.02f, 0.042f, 0.05f, 0.22f, STEEL),                   // action
            p(0.034f, 0.02f, 0.09f, 0.03f, 0.01f, 0.01f, STEEL),                // bolt handle
            p(0.05f, 0.012f, 0.09f, 0.016f, 0.016f, 0.016f, BLACK),             // bolt knob
            p(0f, -0.03f, -0.22f, 0.052f, 0.055f, 0.42f, OD_GREEN),             // forend
            p(0f, -0.035f, 0.08f, 0.05f, 0.06f, 0.24f, OD_GREEN),               // stock body
            p(0f, 0.012f, -0.64f, 0.026f, 0.026f, 0.56f, DARK_STEEL),           // heavy barrel
            p(0f, -0.05f, 0.03f, 0.012f, 0.012f, 0.07f, BLACK),                 // trigger guard
            p(0f, -0.085f, 0.13f, 0.036f, 0.1f, 0.048f, OD_GREEN, -24f),        // grip
            p(0f, -0.03f, 0.33f, 0.046f, 0.1f, 0.26f, OD_GREEN, -4f),           // butt stock
            p(0f, -0.03f, 0.465f, 0.048f, 0.11f, 0.015f, BLACK),                // recoil pad
        ) + scope(y = 0.09f, z = 0.0f, length = 0.26f, tube = 0.034f),
        gripY = -0.085f, gripZ = 0.13f, supportZ = -0.3f, muzzleY = 0.012f, muzzleZ = -0.92f,
    )

    /** AWM: green chassis with a thumbhole stock and cheek piece, detachable magazine, muzzle brake, 3–12× scope. */
    private fun awm() = GunModel(
        listOf(
            p(0f, 0.012f, 0.02f, 0.044f, 0.05f, 0.24f, BLACK),                   // action
            p(0.036f, 0.02f, 0.1f, 0.032f, 0.01f, 0.01f, BLACK),                // bolt handle
            p(0.052f, 0.012f, 0.1f, 0.018f, 0.018f, 0.018f, BLACK),             // bolt knob
            p(0f, -0.03f, -0.2f, 0.056f, 0.06f, 0.36f, OD_GREEN),               // chassis forend
            p(0f, 0.012f, -0.63f, 0.026f, 0.026f, 0.54f, BLACK),                // barrel
            p(0f, 0.012f, -0.92f, 0.036f, 0.036f, 0.07f, BLACK),                // muzzle brake
            p(0f, -0.075f, -0.02f, 0.03f, 0.06f, 0.08f, BLACK),                 // 5-round magazine
            p(0f, -0.05f, 0.07f, 0.012f, 0.012f, 0.07f, BLACK),                 // trigger guard
            p(0f, -0.09f, 0.15f, 0.036f, 0.1f, 0.045f, OD_GREEN, -10f),         // thumbhole grip
            p(0f, 0.02f, 0.3f, 0.044f, 0.035f, 0.24f, OD_GREEN),                // upper stock
            p(0f, -0.07f, 0.33f, 0.044f, 0.05f, 0.2f, OD_GREEN),                // lower stock
            p(0f, -0.03f, 0.43f, 0.046f, 0.14f, 0.04f, OD_DARK),                // butt
            p(0f, 0.045f, 0.3f, 0.038f, 0.02f, 0.1f, OD_DARK),                  // cheek piece
            p(0.014f, -0.07f, -0.34f, 0.008f, 0.008f, 0.22f, BLACK),            // folded bipod
            p(-0.014f, -0.07f, -0.34f, 0.008f, 0.008f, 0.22f, BLACK),
        ) + scope(y = 0.095f, z = 0.0f, length = 0.28f, tube = 0.036f),
        gripY = -0.09f, gripZ = 0.15f, supportZ = -0.28f, muzzleY = 0.012f, muzzleZ = -0.96f,
    )

    /** Barrett M82A1: huge receiver, fluted .50 barrel with its double-chamber muzzle brake, carry handle, bipod. */
    private fun m82() = GunModel(
        listOf(
            p(0f, 0.01f, -0.02f, 0.066f, 0.09f, 0.5f, PARKERIZED),               // upper receiver
            p(0f, -0.045f, 0.05f, 0.06f, 0.05f, 0.36f, PARKERIZED),              // lower receiver
            p(0f, 0.062f, -0.05f, 0.026f, 0.014f, 0.42f, BLACK),                // top rail
            p(0f, 0.01f, -0.42f, 0.06f, 0.06f, 0.3f, PARKERIZED),               // barrel shroud
            p(0f, 0.01f, -0.78f, 0.034f, 0.034f, 0.44f, DARK_STEEL),            // .50 barrel
            p(0f, 0.01f, -1.03f, 0.07f, 0.055f, 0.09f, BLACK),                  // muzzle brake
            p(0f, 0.01f, -1.03f, 0.074f, 0.03f, 0.03f, DARK_STEEL),             // brake ports
            p(0f, -0.1f, -0.1f, 0.04f, 0.1f, 0.1f, PARKERIZED),                 // 10-round magazine
            p(0f, -0.07f, 0.12f, 0.014f, 0.012f, 0.08f, PARKERIZED),            // trigger guard
            p(0f, -0.11f, 0.19f, 0.04f, 0.11f, 0.05f, POLYMER, -16f),           // pistol grip
            p(0f, -0.02f, 0.4f, 0.056f, 0.11f, 0.2f, PARKERIZED),               // stock
            p(0f, -0.02f, 0.51f, 0.06f, 0.13f, 0.03f, BLACK),                   // recoil pad
            p(0f, -0.1f, 0.44f, 0.016f, 0.06f, 0.016f, BLACK),                  // monopod
            p(0f, 0.11f, -0.2f, 0.022f, 0.02f, 0.14f, BLACK),                   // carry handle
            p(0.016f, -0.04f, -0.52f, 0.012f, 0.012f, 0.3f, BLACK),             // folded bipod
            p(-0.016f, -0.04f, -0.52f, 0.012f, 0.012f, 0.3f, BLACK),
        ) + scope(y = 0.12f, z = 0.05f, length = 0.28f, tube = 0.042f),
        gripY = -0.11f, gripZ = 0.19f, supportZ = -0.36f, muzzleY = 0.01f, muzzleZ = -1.08f,
    )
}

/**
 * Real photos of the guns for the loadout screen: `assets/guns/<gun>.png` (or .jpg / .webp),
 * named after the [Weapon] (e.g. `ak47.png`), each facing left. Credits are in
 * `assets/guns/CREDITS.txt`.
 */
object GunPhotos {
    private val extensions = listOf("png", "webp", "jpg")

    /** The gun's photo, or null if there isn't one (then draw it with [GunIcon]). */
    fun load(context: android.content.Context, weapon: Weapon): Bitmap? {
        for (ext in extensions) {
            val bitmap = runCatching {
                context.assets.open("guns/${weapon.name.lowercase()}.$ext").use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

    /** Who took the photos and under which licences. */
    fun credits(context: android.content.Context): String =
        runCatching { context.assets.open("guns/CREDITS.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
}

/** Side views of the guns drawn from the same boxes as the 3D guns, for a gun without a photo. */
object GunIcon {
    /** The gun seen from its right side, muzzle to the right, fitted into [width] × [height]. */
    fun draw(weapon: Weapon, width: Int, height: Int): Bitmap {
        val model = GunModels.of(weapon)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Side view: across the picture is -z (towards the muzzle), up is +y.
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (part in model.parts) {
            val r = maxOf(part.sz, part.sy) / 2f
            minX = minOf(minX, -part.z - r); maxX = maxOf(maxX, -part.z + r)
            minY = minOf(minY, part.y - r); maxY = maxOf(maxY, part.y + r)
        }
        val pad = 0.06f * width
        val scale = minOf((width - 2 * pad) / (maxX - minX), (height - 2 * pad) / (maxY - minY))
        val cx = width / 2f - (minX + maxX) / 2f * scale
        val cy = height / 2f + (minY + maxY) / 2f * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, width / 300f)
            color = 0x55FFFFFF
        }
        // Far side first, so parts nearer the viewer (larger x) cover them.
        for (part in model.parts.sortedBy { it.x }) {
            paint.color = lighten(part.color)
            canvas.save()
            canvas.translate(cx - part.z * scale, cy - part.y * scale)
            // Tipping the front up turns the box anticlockwise in this view.
            canvas.rotate(-part.pitch)
            val rect = RectF(-part.sz / 2f * scale, -part.sy / 2f * scale, part.sz / 2f * scale, part.sy / 2f * scale)
            canvas.drawRect(rect, paint)
            canvas.drawRect(rect, outline)
            canvas.restore()
        }
        return bitmap
    }

    /** A little lighter than in 3D, where the lights brighten it; black guns on a dark card still show. */
    private fun lighten(color: Int): Int {
        fun ch(s: Int) = (((color shr s) and 0xFF) * 0.8f + 255 * 0.2f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
