package com.example.beirutrun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.example.beirutrun.online.RoomTeams

/**
 * A team players can join in a room.
 *
 * @property id Stable id. It is saved with each player online, and it names the flag image:
 *   `app/src/main/assets/flags/<id>.png` (or `.jpg` / `.webp`).
 * @property name Shown in the team picker and next to players' names.
 * @property color Used for the name tag, the minimap dot and as the placeholder flag.
 * @property uniform The soldiers' uniform colour. Leave it out for army olive tinted towards [color].
 * @property gear Vest, knee pads and boots trim. Leave it out for a darker [color].
 */
data class Team(
    val id: String,
    val name: String,
    val color: Int,
    val uniform: Int = uniformFor(color),
    val gear: Int = shade(color, 0.55f),
)

/** Army olive mixed a little towards the team colour, so teams look like soldiers but differ. */
private fun uniformFor(color: Int): Int = mix(0xFF4B5320.toInt(), color, 0.35f)

private fun mix(a: Int, b: Int, k: Float): Int {
    fun ch(c: Int, s: Int) = (c shr s) and 0xFF
    fun lerp(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * k).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
}

private fun shade(color: Int, k: Float): Int = mix(0xFF000000.toInt(), color, k)

/**
 * The teams. Every room offers the [builtIn] teams, plus the teams its players added (see
 * [RoomTeams]). To add a built-in team, add a line to [builtIn] and put its flag in
 * `app/src/main/assets/flags/<id>.png`. Don't change an existing id once people have played,
 * because players online are stored with their team id.
 *
 * Teams are fictional factions: don't name them after real political parties, militias or other
 * groups, or use their flags (Google Play rejects apps that do). The flags are drawn by
 * `tools/FactionFlags.java`.
 *
 * Every player on a team wears the same soldier uniform with the team flag on both shoulders.
 * To choose the uniform yourself, add the colours, e.g.
 * `Team("golden_lions", "Golden Lions", 0xFFF9A825.toInt(), uniform = 0xFF5B5A2E.toInt(), gear = 0xFF3A3A1E.toInt()),`
 */
object Teams {
    /** Offered in every room. */
    val builtIn = listOf(
        Team("el_lahib", "عشيرة اللهيب", 0xFF6A1B9A.toInt()),
        Team("corniche_sharks", "Corniche Sharks", 0xFF1565C0.toInt()),
        Team("golden_lions", "Golden Lions", 0xFFF9A825.toInt()),
    )

    /**
     * No longer offered, but still recognised: players on older versions of the app can be on
     * them, and should still get their flag and uniform.
     */
    private val retired = listOf(
        Team("evergreen_squad", "Evergreen Squad", 0xFF2E7D32.toInt()),
        Team("raouche_eagles", "Raouche Eagles", 0xFFC62828.toInt()),
        Team("phoenix_legion", "Phoenix Legion", 0xFFEF6C00.toInt()),
        Team("summit_rangers", "Summit Rangers", 0xFF00897B.toInt()),
    )

    /** The teams to choose from in the current room: the built-in ones, then the ones players added. */
    fun inRoom(): List<Team> = builtIn + RoomTeams.teams()

    fun byId(id: String?): Team? =
        builtIn.firstOrNull { it.id == id } ?: RoomTeams.byId(id) ?: retired.firstOrNull { it.id == id }
}

/**
 * Loads team flags: a team added in the room has its uploaded flag (see [RoomTeams]); built-in
 * teams have theirs in `assets/flags/`. Draws a stand-in flag when there is none.
 */
object TeamFlags {
    private val extensions = listOf("png", "jpg", "jpeg", "webp")

    /** A new bitmap each time (callers may recycle it). */
    fun load(context: Context, team: Team): Bitmap {
        RoomTeams.flag(team.id)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        for (ext in extensions) {
            val bitmap = runCatching {
                context.assets.open("flags/${team.id}.$ext").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return placeholder(team)
    }

    /** A plain flag in the team colour with its initials. */
    private fun placeholder(team: Team): Bitmap {
        val w = 300
        val h = 200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(team.color)
        val initials = team.name.split(' ').filter { it.isNotBlank() }.take(3)
            .joinToString("") { it.first().uppercase() }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(initials, w / 2f, h / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }
}
