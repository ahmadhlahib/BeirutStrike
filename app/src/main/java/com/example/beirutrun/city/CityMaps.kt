package com.example.beirutrun.city

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * A playable area of Beirut. Its files are made by tools/build_maps.sh (see tools/maps.txt):
 * `assets/maps/<id>.bin` (the map) and `assets/maps/<id>_preview.png` (a top-down picture).
 */
data class CityMapInfo(
    val id: String,
    val name: String,
    /** Which way you face when you arrive, degrees clockwise from north (-90 west, 90 east). */
    val startHeadingDegrees: Float = 0f,
    /** The kind of buildings the area is known for (colours and wall styles). */
    val look: CityLook = CityLook.MIXED,
) {
    /** The starting direction in the game's yaw convention (radians). */
    val startYaw get() = Math.toRadians(startHeadingDegrees.toDouble()).toFloat()

    val asset get() = "maps/$id.bin"
    val previewAsset get() = "maps/${id}_preview.png"

    fun loadPreview(context: Context): Bitmap? =
        runCatching { context.assets.open(previewAsset).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    fun load(context: Context): CityMap = context.assets.open(asset).use { CityMap.load(it) }
}

/**
 * What an area's buildings look like: the share of each wall style (sandstone, cream with
 * balconies, concrete, white seafront tower), and the height above which a building is glass.
 */
enum class CityLook(val sandstone: Float, val cream: Float, val concrete: Float, val white: Float, val glassAbove: Float) {
    /** Downtown: restored Ottoman and French-mandate sandstone. */
    SANDSTONE(0.65f, 0.2f, 0.1f, 0.05f, 38f),
    /** The Souks and old lanes: low stone buildings. */
    OLD_STONE(0.8f, 0.1f, 0.1f, 0f, 60f),
    /** Hamra: dense mid-rise blocks, cream and concrete with balconies. */
    MIDRISE(0.15f, 0.4f, 0.35f, 0.1f, 45f),
    /** Raouche and Ain El Mreisseh: white residential towers facing the sea. */
    SEAFRONT(0.1f, 0.25f, 0.15f, 0.5f, 60f),
    MIXED(0.4f, 0.3f, 0.2f, 0.1f, 35f),
    /** A mountain village (Kfarnabrakh): stone and cream houses, some bare concrete, no towers. */
    VILLAGE(0.55f, 0.3f, 0.15f, 0f, 80f),
}

/**
 * The maps a room creator can choose from, in the order shown. To add one: add it to
 * tools/maps.txt, run `bash tools/build_maps.sh <id>`, then add a line here with the same id.
 */
object CityMaps {
    val all = listOf(
        // Martyrs' Square, facing the Al-Amin Mosque and the sea to the north.
        CityMapInfo("downtown", "Beirut Downtown", 0f, CityLook.SANDSTONE),
        // Hamra Street, looking west along it.
        CityMapInfo("hamra", "Hamra", -90f, CityLook.MIDRISE),
        CityMapInfo("souks", "Beirut Souks", 0f, CityLook.OLD_STONE),
        // On the Corniche, looking out to sea.
        CityMapInfo("ain_el_mreisseh", "Ain El Mreisseh", 0f, CityLook.SEAFRONT),
        // On the Corniche, facing Pigeon Rocks.
        CityMapInfo("raouche", "Raouche", -104f, CityLook.SEAFRONT),
        // A village in the Chouf mountains, with its real hills; facing north over the valley.
        CityMapInfo("kfarnabrakh", "Kfarnabrakh", 0f, CityLook.VILLAGE),
    )

    val default = all.first()

    /**
     * The play-area sizes a room creator can choose: the side of the square players can walk in,
     * in metres, around the map's start point. null means the whole map.
     */
    val sizes: List<Int?> = listOf(200, 400, 600, 800, null)
    val defaultSize: Int? = 400

    /** The map with this id (or a room's map value, see [roomValue]); unknown ids get Downtown. */
    fun byId(id: String?): CityMapInfo = all.firstOrNull { it.id == id?.substringBefore(':') } ?: default

    /**
     * What a room stores as its map: the map id, plus `:<size>` for a limited play area
     * (e.g. `downtown:200`). Rooms without a size use the whole map.
     */
    fun roomValue(map: CityMapInfo, size: Int?) = if (size == null) map.id else "${map.id}:$size"

    /** The play-area size in a room's map value; null = the whole map. */
    fun sizeOf(roomValue: String?): Int? = roomValue?.substringAfter(':', "")?.toIntOrNull()?.takeIf { it > 0 }

    /** Only the maps whose files are actually in the app (a map may not be built yet). */
    fun available(context: Context): List<CityMapInfo> {
        val files = context.assets.list("maps")?.toSet().orEmpty()
        return all.filter { "${it.id}.bin" in files }
    }
}
