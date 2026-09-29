package com.example.beirutrun.city

/**
 * The guns every player carries. [id] is shared with the other phones (so they draw the right gun
 * and play the right sound); ammo is counted separately for each gun.
 *
 * @property fireInterval Seconds between shots at the fastest.
 * @property automatic Keeps firing while Shoot is held; otherwise one shot per press.
 * @property startAmmo Bullets on arriving in the city and after each respawn.
 * @property spread How far off the aim a bullet can go, radians (halved through a scope).
 */
enum class Weapon(
    val id: String,
    val fireInterval: Float,
    val automatic: Boolean,
    val startAmmo: Int,
    val spread: Float,
) {
    PISTOL("pistol", fireInterval = 1.0f, automatic = false, startAmmo = 12, spread = 0.004f),
    AK47("ak47", fireInterval = 0.1f, automatic = true, startAmmo = 30, spread = 0.018f);

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: AK47
    }
}

/** What lies in the street to be picked up: 10 bullets for one gun, or a scope for the AK-47. */
enum class PickupKind(val id: String, val weapon: Weapon?) {
    PISTOL_AMMO("pistol_ammo", Weapon.PISTOL),
    AK_AMMO("ak_ammo", Weapon.AK47),
    SCOPE("scope", null);

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }

        /** Bullets in one pack. */
        const val PACK_SIZE = 10

        /**
         * The room's pickup spots: each slot always holds this kind, and after it is taken it comes
         * back somewhere else (see [respawnMs]).
         */
        val SLOTS: List<PickupKind> = List(5) { AK_AMMO } + List(4) { PISTOL_AMMO } + List(2) { SCOPE }

        fun respawnMs(kind: PickupKind) = if (kind == SCOPE) 60_000L else 20_000L
    }
}

/** A pickup lying at (x, z), in pickup slot [slot] (see [PickupKind.SLOTS]). */
data class Pickup(val slot: Int, val kind: PickupKind, val x: Float, val z: Float)
