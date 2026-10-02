package com.example.beirutrun.city

/**
 * The grenades a player carries besides the three guns, all of them thrown with the grenade
 * button. Distances are scaled to the game like the guns' (see [Weapon]), and fuses shortened so a
 * throw doesn't take the whole fight. [id] is shared with the other phones, which fly the same
 * grenade from the same throw and show what it does.
 *
 * @property carried How many a player has on arriving in the city and after each respawn.
 * @property fuseSeconds From the throw to going off. One that goes off on [impact] still bursts
 *   after this long if it never hits anything.
 * @property impact Goes off the moment it hits the ground or a wall (a bottle breaking).
 * @property radius How far what it does reaches, metres: the blast, the blinding flash, the
 *   smoke cloud, the pool of fire.
 * @property seconds How long it lasts once it has gone off (the smoke, the fire); 0 = at once.
 */
enum class GrenadeKind(
    val id: String,
    val displayName: String,
    val carried: Int,
    val fuseSeconds: Float,
    val impact: Boolean,
    val radius: Float,
    val seconds: Float,
    /** The grenade's colour, for the HUD button and the grenade in flight. */
    val color: Int,
) {
    /** M67 fragmentation grenade: a blast that takes more hearts the closer it is, through no wall. */
    FRAG("frag", "Frag", carried = 2, fuseSeconds = 3f, impact = false, radius = 7f, seconds = 0f,
        color = 0xFF6B8E23.toInt()),
    /** M84 stun grenade: a blinding flash and a bang that leaves the ears ringing; no damage. */
    FLASH("flash", "Flashbang", carried = 2, fuseSeconds = 1.6f, impact = false, radius = 24f, seconds = 0f,
        color = 0xFFB0BEC5.toInt()),
    /** M18 smoke grenade: a thick grey cloud to hide behind, that aiming can't see through. */
    SMOKE("smoke", "Smoke", carried = 1, fuseSeconds = 1.8f, impact = false, radius = 5.5f, seconds = 20f,
        color = 0xFF78909C.toInt()),
    /** Molotov cocktail: breaks where it lands and burns, a heart a second for anyone standing in it. */
    MOLOTOV("molotov", "Molotov", carried = 1, fuseSeconds = 4f, impact = true, radius = 3.2f, seconds = 8f,
        color = 0xFFFF7043.toInt());

    /** The most a player can carry, picking more up in the street: twice what they start with. */
    val most get() = carried * 2

    companion object {
        /** A grenade by its id; null for one this version doesn't know (from a newer app). */
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}
