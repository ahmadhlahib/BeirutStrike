package com.example.beirutrun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.beirutrun.city.GunSlot
import com.example.beirutrun.city.Weapon
import com.example.beirutrun.progression.PlayerProgress
import com.example.beirutrun.solo.BotDifficulty
import com.example.beirutrun.solo.SoloSettings
import java.io.File

/** The logged-in player: their name, room, team, face photo and where they last stood. */
object Session {
    private const val PREFS = "session"
    private const val KEY_NAME = "name"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun setName(context: Context, name: String) =
        prefs(context).edit().putString(KEY_NAME, name).apply()

    fun logout(context: Context) =
        prefs(context).edit().remove(KEY_NAME).remove(KEY_ROOM_ID).remove(KEY_ROOM_NAME).remove(KEY_ROOM_MAP).apply()

    /**
     * Forgets everything about the player on this phone ("Delete my data"): name, room, team,
     * view, saved positions, XP and rank, face photos (theirs and downloaded ones) and the online cache.
     */
    fun deleteAll(context: Context) {
        prefs(context).edit().clear().apply()
        context.getSharedPreferences("online", Context.MODE_PRIVATE).edit().clear().apply()
        PlayerProgress.clear(context)
        File(context.filesDir, "faces").deleteRecursively()
        File(context.filesDir, "faces_remote").deleteRecursively()
        facesDirReady = false
    }

    /** The gun the player carries in [slot] (see LoadoutActivity); the default one until they choose. */
    fun gun(context: Context, slot: GunSlot): Weapon =
        prefs(context).getString("gun_${slot.name.lowercase()}", null)
            ?.let { id -> Weapon.entries.firstOrNull { it.id == id && it.slot == slot } }
            ?: Weapon.defaults.getValue(slot)

    fun setGun(context: Context, weapon: Weapon) =
        prefs(context).edit().putString("gun_${weapon.slot.name.lowercase()}", weapon.id).apply()

    /** The character the player plays as (see Characters); null until they choose. */
    fun character(context: Context): String? = prefs(context).getString(KEY_CHARACTER, null)

    fun setCharacter(context: Context, id: String) = prefs(context).edit().putString(KEY_CHARACTER, id).apply()

    /** Whether the player's face photo goes on their character's head (off unless they turn it on). */
    fun faceOnCharacter(context: Context): Boolean = prefs(context).getBoolean(KEY_FACE_ON_CHARACTER, false)

    fun setFaceOnCharacter(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_FACE_ON_CHARACTER, on).apply()

    /** The online room the player is in (null = not chosen yet). */
    fun roomId(context: Context): String? = prefs(context).getString(KEY_ROOM_ID, null)
    fun roomName(context: Context): String? = prefs(context).getString(KEY_ROOM_NAME, null)

    /** Id of the room's map (see CityMaps); null means the default map. */
    fun roomMap(context: Context): String? = prefs(context).getString(KEY_ROOM_MAP, null)

    /** Joins online room [id] (which ends any solo game), or none with null. */
    fun setRoom(context: Context, id: String?, name: String?, map: String? = null) =
        prefs(context).edit().putString(KEY_ROOM_ID, id).putString(KEY_ROOM_NAME, name).putString(KEY_ROOM_MAP, map)
            .apply { if (id != null) putBoolean(KEY_SOLO, false) }.apply()

    /** Id of the chosen team (see [Teams]); null = not chosen yet. */
    fun teamId(context: Context): String? = prefs(context).getString(KEY_TEAM, null)

    fun setTeamId(context: Context, id: String) = prefs(context).edit().putString(KEY_TEAM, id).apply()

    /** Whether the maps show roughly where enemies are (red circles, see EnemyAreas); on unless turned off. */
    fun enemyAreas(context: Context): Boolean = prefs(context).getBoolean(KEY_ENEMY_AREAS, true)

    fun setEnemyAreas(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENEMY_AREAS, on).apply()

    /** Whether the player last chose the first-person view (through the soldier's eyes). */
    fun firstPerson(context: Context): Boolean = prefs(context).getBoolean(KEY_FIRST_PERSON, false)

    fun setFirstPerson(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_FIRST_PERSON, on).apply()

    /** The logged-in player's face photo; each name on this device keeps its own face. */
    fun faceFile(context: Context): File = faceFileFor(context, name(context).orEmpty())

    fun faceFileFor(context: Context, playerName: String): File {
        val key = playerName.trim().lowercase().replace(NON_ALPHANUMERIC, "_")
        return File(facesDir(context), "face_$key.png")
    }

    private fun facesDir(context: Context): File {
        val dir = File(context.filesDir, "faces")
        if (!facesDirReady) {
            dir.mkdirs()
            facesDirReady = true
        }
        return dir
    }

    @Volatile private var facesDirReady = false
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    fun loadFace(context: Context): Bitmap? = loadFaceFor(context, name(context).orEmpty())

    fun loadFaceFor(context: Context, playerName: String): Bitmap? =
        faceFileFor(context, playerName).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Where the player last stood on [map]: x, z and camera yaw (each map remembers its own). */
    fun position(context: Context, map: String): Triple<Float, Float, Float>? {
        val p = prefs(context)
        if (!p.contains("${KEY_X}_$map")) return null
        return Triple(p.getFloat("${KEY_X}_$map", 0f), p.getFloat("${KEY_Z}_$map", 0f), p.getFloat("${KEY_YAW}_$map", 0f))
    }

    fun savePosition(context: Context, map: String, x: Float, z: Float, yaw: Float) =
        prefs(context).edit()
            .putFloat("${KEY_X}_$map", x).putFloat("${KEY_Z}_$map", z).putFloat("${KEY_YAW}_$map", yaw)
            .apply()

    /** The solo game against bots being played (see SoloMatch), or null when playing online. */
    fun solo(context: Context): SoloSettings? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SOLO, false)) return null
        return soloChoice(context)
    }

    /** The last solo settings chosen (for the setup screen), playing solo or not. */
    fun soloChoice(context: Context): SoloSettings {
        val p = prefs(context)
        return SoloSettings(
            bots = p.getInt(KEY_SOLO_BOTS, 4).coerceIn(SoloSettings.MIN_BOTS, SoloSettings.MAX_BOTS),
            difficulty = BotDifficulty.byId(p.getString(KEY_SOLO_DIFFICULTY, null)),
            allies = p.getBoolean(KEY_SOLO_ALLIES, false),
            durationMs = p.getLong(KEY_SOLO_DURATION, 5 * 60_000L),
        )
    }

    /** Starts playing solo with [settings] on [map] (a CityMaps room value), or back to online with null. */
    fun setSolo(context: Context, settings: SoloSettings?, map: String? = null) {
        val edit = prefs(context).edit().putBoolean(KEY_SOLO, settings != null)
        if (settings != null) {
            edit.putInt(KEY_SOLO_BOTS, settings.bots)
                .putString(KEY_SOLO_DIFFICULTY, settings.difficulty.id)
                .putBoolean(KEY_SOLO_ALLIES, settings.allies)
                .putLong(KEY_SOLO_DURATION, settings.durationMs)
                // No online room while solo; the map is the one chosen.
                .putString(KEY_ROOM_ID, null).putString(KEY_ROOM_NAME, null).putString(KEY_ROOM_MAP, map)
        }
        edit.apply()
    }

    private const val KEY_SOLO = "solo"
    private const val KEY_SOLO_BOTS = "solo_bots"
    private const val KEY_SOLO_DIFFICULTY = "solo_difficulty"
    private const val KEY_SOLO_ALLIES = "solo_allies"
    private const val KEY_SOLO_DURATION = "solo_duration"
    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_ROOM_NAME = "room_name"
    private const val KEY_ROOM_MAP = "room_map"
    private const val KEY_TEAM = "team"
    private const val KEY_CHARACTER = "character"
    private const val KEY_FACE_ON_CHARACTER = "face_on_character"
    private const val KEY_FIRST_PERSON = "first_person"
    private const val KEY_ENEMY_AREAS = "enemy_areas"
    // "v2": maps got new start points; older saved positions are ignored.
    private const val KEY_X = "pos2_x"
    private const val KEY_Z = "pos2_z"
    private const val KEY_YAW = "pos2_yaw"
}
