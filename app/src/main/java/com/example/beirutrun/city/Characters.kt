package com.example.beirutrun.city

import android.content.Context
import org.json.JSONObject

/**
 * A character a player can play as.
 *
 * @property id Shared with the other phones, so they draw the same character. The built-in
 *   soldier is [Characters.SOLDIER]; the others are named after their folder.
 * @property folder `assets/models/characters/<id>`, holding `character.glb` and its animation
 *   clips (see SoldierRig.load); null for the built-in soldier.
 * @property ownFace The character has a real face of its own, so a player's face photo is never
 *   put on it.
 */
data class Character(
    val id: String, val name: String, val folder: String?, val ownFace: Boolean,
    /** Dances the character can do (on the character screen, and when they win a game). */
    val dances: List<Dance> = emptyList(),
)

/** A dance: its animation file in the character's folder (without .glb) and the name shown on its button. */
data class Dance(val file: String, val name: String) {
    /** The clip name the dance plays as (see SoldierRig.load). */
    val clip get() = "Dance:$file"
}

/**
 * The characters: the built-in soldier, then every folder in `assets/models/characters/` that
 * has a `character.glb`. To add one, convert the Mixamo character and its clips to `.glb` (see
 * the README) into a new folder there, with a `character.json` such as
 * `{"name": "Ahmad El Lahib", "ownFace": true}`, and optionally dances:
 * `"dances": [{"file": "dance_wave", "name": "Wave hip-hop"}]` (each file a Mixamo animation
 * converted to .glb, e.g. with tools/StripAnimation.java to keep only the motion).
 */
object Characters {
    const val SOLDIER = "soldier"
    private const val DIR = "models/characters"

    @Volatile private var cached: List<Character>? = null

    fun all(context: Context): List<Character> = cached ?: load(context).also { cached = it }

    /** The character with [id], or the built-in soldier for an unknown one (e.g. from a newer version). */
    fun byId(context: Context, id: String?): Character =
        all(context).firstOrNull { it.id == id } ?: all(context).first()

    private fun load(context: Context): List<Character> {
        val soldier = Character(SOLDIER, "Soldier", folder = null, ownFace = false)
        val folders = runCatching { context.assets.list(DIR)?.toList() }.getOrNull().orEmpty().sorted()
        val found = folders.mapNotNull { id ->
            val folder = "$DIR/$id"
            val files = runCatching { context.assets.list(folder)?.toList() }.getOrNull().orEmpty()
            if ("character.glb" !in files) return@mapNotNull null
            val info = runCatching {
                JSONObject(context.assets.open("$folder/character.json").bufferedReader().use { it.readText() })
            }.getOrNull()
            Character(
                id = id,
                name = info?.optString("name")?.takeIf { it.isNotBlank() } ?: id.replaceFirstChar { it.uppercase() },
                folder = folder,
                ownFace = info?.optBoolean("ownFace", false) ?: false,
                // Only dances whose animation file is there.
                dances = info?.optJSONArray("dances")?.let { list ->
                    List(list.length()) { i -> list.getJSONObject(i).let { Dance(it.getString("file"), it.optString("name", it.getString("file"))) } }
                        .filter { "${it.file}.glb" in files }
                }.orEmpty(),
            )
        }
        return listOf(soldier) + found
    }
}
