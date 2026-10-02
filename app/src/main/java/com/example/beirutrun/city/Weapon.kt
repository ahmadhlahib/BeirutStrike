package com.example.beirutrun.city

/** Which of the three guns a player carries a weapon is: one of each is chosen before playing. */
enum class GunSlot { PISTOL, PRIMARY, SNIPER }

/** How a gun's shot sounds (see SoundSynth.gunshot): from a pistol's crack to a .50's boom. */
enum class ShotSound { PISTOL, MAGNUM, SMG, RIFLE, LMG, SNIPER, FIFTY }

/**
 * Every gun in the game, from the real guns' specifications scaled to the game: distances are
 * about a fifth of the real ones (so a sniper can cross a map but a pistol can't), and bullets fly
 * at a tenth of their real speed so they can be seen. [id] is shared with the other phones (so
 * they draw the right gun and play the right sound).
 *
 * @property magazine Rounds in one magazine (or belt, for the M249).
 * @property magazines Magazines carried, the loaded one included: the gun starts with
 *   [magazine] × [magazines] rounds.
 * @property reloadSeconds Changing the magazine (or belt, or loading a bolt action's rounds), as
 *   long as it takes with the real gun.
 * @property fireInterval Seconds between shots at the fastest: 60 / rounds per minute for
 *   automatics, how fast the trigger (or the bolt) can be worked for the others.
 * @property automatic Keeps firing while Shoot is held; otherwise one shot per press.
 * @property boltAction Each shot is followed by working the bolt (heard, and part of [fireInterval]).
 * @property range How far a bullet flies, metres.
 * @property bulletSpeed Metres per second.
 * @property damage Hearts one hit takes (a player has 5).
 * @property hipSpread How far off the aim a bullet can go, radians, when not using a scope.
 * @property scopedSpread The same through a scope (sniper rifles are only accurate scoped).
 * @property recoil How far each shot kicks the aim up in first person, radians.
 * @property moveSpeed Walking and running speed with the gun in hand (1 = normal; heavy guns are slower).
 * @property zooms The built-in scope's magnifications, lowest first; empty for a gun without one
 *   (a scope found in the street then fits a primary, see [PickupKind.SCOPE]).
 */
enum class Weapon(
    val id: String,
    val displayName: String,
    val slot: GunSlot,
    val magazine: Int,
    val magazines: Int,
    val reloadSeconds: Float,
    val fireInterval: Float,
    val automatic: Boolean,
    val range: Float,
    val bulletSpeed: Float,
    val damage: Int,
    val hipSpread: Float,
    val scopedSpread: Float,
    val recoil: Float,
    val moveSpeed: Float,
    val sound: ShotSound,
    val zooms: List<Float> = emptyList(),
    val boltAction: Boolean = false,
) {
    // ---- Pistols: 6 magazines each --------------------------------------------------------
    /** Beretta M9 (92FS), 9 mm: the US Army's sidearm, 15 rounds, 381 m/s. */
    M9("pistol", "Beretta M9", GunSlot.PISTOL, magazine = 15, magazines = 6, reloadSeconds = 2.2f,
        fireInterval = 0.2f, automatic = false, range = 40f, bulletSpeed = 38f, damage = 1,
        hipSpread = 0.008f, scopedSpread = 0.008f, recoil = 0.02f, moveSpeed = 1.05f, sound = ShotSound.PISTOL),
    /** Glock 17, 9 mm: lighter polymer frame, 17 rounds, a quicker trigger, 375 m/s. */
    GLOCK17("glock17", "Glock 17", GunSlot.PISTOL, magazine = 17, magazines = 6, reloadSeconds = 2.0f,
        fireInterval = 0.17f, automatic = false, range = 38f, bulletSpeed = 37f, damage = 1,
        hipSpread = 0.009f, scopedSpread = 0.009f, recoil = 0.018f, moveSpeed = 1.05f, sound = ShotSound.PISTOL),
    /** IMI Desert Eagle, .50 AE: heavy, 7 rounds, big recoil and a slow follow-up, 470 m/s. */
    DEAGLE("deagle", "Desert Eagle", GunSlot.PISTOL, magazine = 7, magazines = 6, reloadSeconds = 2.6f,
        fireInterval = 0.45f, automatic = false, range = 45f, bulletSpeed = 47f, damage = 2,
        hipSpread = 0.012f, scopedSpread = 0.012f, recoil = 0.05f, moveSpeed = 1.0f, sound = ShotSound.MAGNUM),

    // ---- Primaries ------------------------------------------------------------------------
    /** AK-47, 7.62×39 mm: 30-round curved magazine, 600 rounds a minute, 715 m/s. */
    AK47("ak47", "AK-47", GunSlot.PRIMARY, magazine = 30, magazines = 4, reloadSeconds = 2.6f,
        fireInterval = 0.1f, automatic = true, range = 80f, bulletSpeed = 72f, damage = 1,
        hipSpread = 0.018f, scopedSpread = 0.006f, recoil = 0.006f, moveSpeed = 0.95f, sound = ShotSound.RIFLE),
    /** M4A1 carbine, 5.56 mm, with a 4× ACOG sight: 30 rounds, 800 a minute, 910 m/s, soft recoil. */
    M4("m4", "M4A1", GunSlot.PRIMARY, magazine = 30, magazines = 4, reloadSeconds = 2.4f,
        fireInterval = 0.075f, automatic = true, range = 90f, bulletSpeed = 91f, damage = 1,
        hipSpread = 0.012f, scopedSpread = 0.004f, recoil = 0.004f, moveSpeed = 1.0f, sound = ShotSound.RIFLE,
        zooms = listOf(4f)),
    /** H&K MP5, 9 mm submachine gun: 30 rounds, 800 a minute, light and steady but short-ranged, 400 m/s. */
    MP5("mp5", "MP5", GunSlot.PRIMARY, magazine = 30, magazines = 4, reloadSeconds = 2.5f,
        fireInterval = 0.075f, automatic = true, range = 55f, bulletSpeed = 40f, damage = 1,
        hipSpread = 0.014f, scopedSpread = 0.007f, recoil = 0.003f, moveSpeed = 1.08f, sound = ShotSound.SMG),
    /** RPK, 7.62×39 mm light machine gun: longer, heavier barrel with a bipod, 40-round magazine, 745 m/s. */
    RPK("rpk", "RPK", GunSlot.PRIMARY, magazine = 40, magazines = 4, reloadSeconds = 3.2f,
        fireInterval = 0.1f, automatic = true, range = 100f, bulletSpeed = 75f, damage = 1,
        hipSpread = 0.016f, scopedSpread = 0.005f, recoil = 0.005f, moveSpeed = 0.9f, sound = ShotSound.LMG),
    /** FN M249 SAW, 5.56 mm: belt-fed from 100-round pouches, 750 a minute; changing the belt is slow. */
    M249("m249", "M249 SAW", GunSlot.PRIMARY, magazine = 100, magazines = 3, reloadSeconds = 6.5f,
        fireInterval = 0.08f, automatic = true, range = 100f, bulletSpeed = 91f, damage = 1,
        hipSpread = 0.022f, scopedSpread = 0.007f, recoil = 0.005f, moveSpeed = 0.8f, sound = ShotSound.LMG),

    // ---- Sniper rifles: 4 magazines each, always with a scope --------------------------------
    /** SVD Dragunov, 7.62×54R: semi-automatic, 10 rounds, PSO-1 scope, 830 m/s. */
    SVD("svd", "SVD Dragunov", GunSlot.SNIPER, magazine = 10, magazines = 4, reloadSeconds = 3.0f,
        fireInterval = 0.5f, automatic = false, range = 170f, bulletSpeed = 83f, damage = 3,
        hipSpread = 0.04f, scopedSpread = 0.0015f, recoil = 0.03f, moveSpeed = 0.95f, sound = ShotSound.SNIPER,
        zooms = listOf(2f, 4f)),
    /** M24 SWS, 7.62×51: bolt action, 5 rounds loaded one by one, 10× scope, 790 m/s. */
    M24("m24", "M24", GunSlot.SNIPER, magazine = 5, magazines = 4, reloadSeconds = 4.5f,
        fireInterval = 1.3f, automatic = false, range = 200f, bulletSpeed = 79f, damage = 4,
        hipSpread = 0.05f, scopedSpread = 0.001f, recoil = 0.04f, moveSpeed = 0.95f, sound = ShotSound.SNIPER,
        zooms = listOf(4f, 10f), boltAction = true),
    /** Accuracy International AWM, .338 Lapua: bolt action, 5-round magazine, 3–12× scope, 936 m/s. */
    AWM("awm", "AWM", GunSlot.SNIPER, magazine = 5, magazines = 4, reloadSeconds = 3.7f,
        fireInterval = 1.2f, automatic = false, range = 215f, bulletSpeed = 94f, damage = 5,
        hipSpread = 0.05f, scopedSpread = 0.0008f, recoil = 0.05f, moveSpeed = 0.9f, sound = ShotSound.SNIPER,
        zooms = listOf(3f, 6f, 12f), boltAction = true),
    /** Barrett M82A1, .50 BMG: semi-automatic anti-materiel rifle, 10 rounds, huge and heavy, 853 m/s. */
    M82("m82", "Barrett M82", GunSlot.SNIPER, magazine = 10, magazines = 4, reloadSeconds = 4.5f,
        fireInterval = 0.8f, automatic = false, range = 230f, bulletSpeed = 85f, damage = 5,
        hipSpread = 0.06f, scopedSpread = 0.0012f, recoil = 0.07f, moveSpeed = 0.8f, sound = ShotSound.FIFTY,
        zooms = listOf(5f, 10f));

    /** Rounds carried when arriving in the city and after each respawn. */
    val startAmmo get() = magazine * magazines

    /** Rounds per minute, for the loadout screen (0 for guns fired one shot at a time). */
    val roundsPerMinute get() = if (automatic) (60f / fireInterval).toInt() else 0

    val hasScope get() = zooms.isNotEmpty()

    companion object {
        /** A gun by its id; older versions of the app call the M9 "pistol" too. Unknown ids: the AK-47. */
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: AK47

        fun inSlot(slot: GunSlot) = entries.filter { it.slot == slot }

        /** What a new player carries. */
        val defaults = mapOf(GunSlot.PISTOL to M9, GunSlot.PRIMARY to AK47, GunSlot.SNIPER to SVD)

        /** The magnification of a scope found in the street (it fits primaries without a built-in one). */
        const val PICKUP_SCOPE_ZOOM = 4f
    }
}

/**
 * What lies in the street to be picked up: a magazine for one of the three guns (whichever the
 * player carries in that slot), a scope for a primary that has none, or a grenade.
 */
enum class PickupKind(val id: String, val slot: GunSlot?, val grenade: GrenadeKind? = null) {
    PISTOL_AMMO("pistol_ammo", GunSlot.PISTOL),
    /** Called "ak_ammo" since the AK-47 was the only primary; it fills any primary. */
    AK_AMMO("ak_ammo", GunSlot.PRIMARY),
    SNIPER_AMMO("sniper_ammo", GunSlot.SNIPER),
    SCOPE("scope", null),
    FRAG_GRENADE("frag_grenade", null, GrenadeKind.FRAG),
    FLASH_GRENADE("flash_grenade", null, GrenadeKind.FLASH),
    SMOKE_GRENADE("smoke_grenade", null, GrenadeKind.SMOKE),
    MOLOTOV("molotov", null, GrenadeKind.MOLOTOV);

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }

        /**
         * The room's pickup spots: each slot always holds this kind, and after it is taken it comes
         * back somewhere else (see [respawnMs]). New kinds go at the end, so older versions of the
         * app, which know fewer slots, still agree on the first ones.
         */
        val SLOTS: List<PickupKind> =
            List(5) { AK_AMMO } + List(4) { PISTOL_AMMO } + List(2) { SCOPE } + List(2) { SNIPER_AMMO } +
                List(2) { FRAG_GRENADE } + List(2) { FLASH_GRENADE } + SMOKE_GRENADE + MOLOTOV

        fun respawnMs(kind: PickupKind) = when (kind) {
            SCOPE -> 60_000L
            SMOKE_GRENADE, MOLOTOV -> 45_000L
            SNIPER_AMMO, FRAG_GRENADE, FLASH_GRENADE -> 40_000L
            else -> 20_000L
        }
    }
}

/** A pickup lying at (x, z), in pickup slot [slot] (see [PickupKind.SLOTS]). */
data class Pickup(val slot: Int, val kind: PickupKind, val x: Float, val z: Float)
