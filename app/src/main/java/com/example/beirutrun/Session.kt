package com.example.beirutrun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
     * view, saved positions, face photos (theirs and downloaded ones) and the online cache.
     */
    fun deleteAll(context: Context) {
        prefs(context).edit().clear().apply()
        context.getSharedPreferences("online", Context.MODE_PRIVATE).edit().clear().apply()
        File(context.filesDir, "faces").deleteRecursively()
        File(context.filesDir, "faces_remote").deleteRecursively()
        facesDirReady = false
    }

    /** The online room the player is in (null = not chosen yet). */
    fun roomId(context: Context): String? = prefs(context).getString(KEY_ROOM_ID, null)
    fun roomName(context: Context): String? = prefs(context).getString(KEY_ROOM_NAME, null)

    /** Id of the room's map (see CityMaps); null means the default map. */
    fun roomMap(context: Context): String? = prefs(context).getString(KEY_ROOM_MAP, null)

    fun setRoom(context: Context, id: String?, name: String?, map: String? = null) =
        prefs(context).edit().putString(KEY_ROOM_ID, id).putString(KEY_ROOM_NAME, name).putString(KEY_ROOM_MAP, map).apply()

    /** Id of the chosen team (see [Teams]); null = not chosen yet. */
    fun teamId(context: Context): String? = prefs(context).getString(KEY_TEAM, null)

    fun setTeamId(context: Context, id: String) = prefs(context).edit().putString(KEY_TEAM, id).apply()

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

    private const val KEY_ROOM_ID = "room_id"
    private const val KEY_ROOM_NAME = "room_name"
    private const val KEY_ROOM_MAP = "room_map"
    private const val KEY_TEAM = "team"
    private const val KEY_FIRST_PERSON = "first_person"
    // "v2": maps got new start points; older saved positions are ignored.
    private const val KEY_X = "pos2_x"
    private const val KEY_Z = "pos2_z"
    private const val KEY_YAW = "pos2_yaw"
}
