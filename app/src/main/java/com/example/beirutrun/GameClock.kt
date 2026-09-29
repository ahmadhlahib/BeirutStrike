package com.example.beirutrun

/** Formats a room's game time for the HUD and the room list. */
object GameClock {
    /** "0:30", "12:05", "1:00:00"; rounds up, so the last second shows as "0:01" rather than "0:00". */
    fun format(ms: Long): String {
        val total = ((ms.coerceAtLeast(0L) + 999) / 1000).toInt()
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
