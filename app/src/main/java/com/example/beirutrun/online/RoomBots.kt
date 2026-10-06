package com.example.beirutrun.online

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.solo.BotDifficulty
import com.example.beirutrun.solo.SoloMatch
import com.example.beirutrun.solo.SoloSettings
import com.google.firebase.database.DataSnapshot

/**
 * A room's bots: how many, how good, and which team they play for ([team]: a team id, or ""
 * for every team in turn, so some fight with each player and some against).
 */
data class RoomBotsConfig(val count: Int, val difficulty: BotDifficulty, val team: String) {
    fun toMap(): Map<String, Any> = mapOf("count" to count, "difficulty" to difficulty.id, "team" to team)

    companion object {
        /** Reads a room's `bots` settings; null when it has none. */
        fun from(s: DataSnapshot): RoomBotsConfig? {
            val count = (s.child("count").value as? Number)?.toInt() ?: return null
            if (count <= 0) return null
            return RoomBotsConfig(
                count.coerceAtMost(SoloSettings.MAX_BOTS),
                BotDifficulty.byId(s.child("difficulty").getValue(String::class.java)),
                s.child("team").getValue(String::class.java).orEmpty(),
            )
        }
    }
}

/**
 * Bots in an online room. One phone, the host, runs them with the solo bots' brain
 * ([SoloMatch]): it sees every player in the room, moves and shoots the bots, and writes them to
 * the room ([OnlineWorld.publishBots]), so every phone draws the same bots and their shots, like
 * players. The host is the player with the lowest id who's here; when the host leaves (no writes
 * for 16 s), the next one takes over (the database rules only allow that once the bots are stale).
 *
 * Hits work like players': a player's hit on a bot goes to the host ([onBotHit]), which kills it
 * when it's out of hearts and marks who killed it, and the killer's phone counts the kill, XP and
 * money as for any player. A bot's hit on a player goes to that player's phone ([OnlineWorld.sendBotHit]).
 * Bots' scores go on the room's scoreboard.
 */
class RoomBots(
    private val city: CityMap,
    private val config: RoomBotsConfig,
    private val online: OnlineWorld,
    /** Every team bots can play for (when they play for all of them). */
    private val allTeams: List<String>,
    private val label: (String) -> String,
    /** A bot's bullet hit me (the host's own player): the city screen takes it like any hit. */
    private val onHitMe: (botId: String, botName: String, damage: Int) -> Unit,
) {
    private var match: SoloMatch? = null
    private var sinceWrite = 0f
    private var sinceStats = 0f
    private var sinceElection = 0f
    /** What the bots' scoreboard rows had when last written, to send only what's new. */
    private val written = HashMap<String, IntArray>()

    /** Whether this phone runs the bots now. */
    val hosting get() = match != null

    /** Moves things on by [dt] seconds; [me] is my own player (null while I can't be seen, e.g. not started). */
    fun tick(dt: Float, me: SoloMatch.Player?, gameOver: Boolean) {
        val myUid = online.uid ?: return
        sinceElection += dt
        if (sinceElection >= ELECTION_SECONDS) {
            sinceElection = 0f
            val host = online.botsHost()
            when {
                // Another phone runs them: stop, if I thought I did (it got there first).
                host != null && host != myUid -> stop()
                // Nobody runs them: the player with the lowest id here takes them on.
                host == null && match == null && me != null && electedIs(myUid) -> start(me)
            }
        }
        val game = match ?: return
        val people = (listOfNotNull(me) + online.humans().map {
            SoloMatch.Player(it.name, it.team, it.x, it.floor, it.z, it.prone, it.dead, it.uid)
        })
        if (!gameOver) for (e in game.update(dt, people)) {
            if (e !is SoloMatch.Event.PlayerHit) continue
            if (e.target == myUid) onHitMe(e.fromUid, e.fromName, e.damage)
            else online.sendBotHit(e.fromUid, e.fromName, e.target, e.damage)
        }
        sinceWrite += dt
        if (sinceWrite >= WRITE_SECONDS) {
            sinceWrite = 0f
            online.publishBots(game.players(online.serverNow()))
        }
        sinceStats += dt
        if (sinceStats >= STATS_SECONDS) {
            sinceStats = 0f
            flushStats(game)
        }
    }

    /** (Host) Player [fromUid] hit bot [botId] for [damage] hearts. */
    fun onBotHit(botId: String, fromUid: String, damage: Int) {
        match?.hitByPlayer(botId, damage, fromUid)
    }

    /** Someone died, killed by [killerUid]: a bot's kill counts on its scoreboard row. */
    fun onPlayerKilled(killerUid: String) {
        match?.playerKilledBy(killerUid)
    }

    /** Someone fired from (x, z) on [team]: bots in earshot turn to look. */
    fun heardShot(x: Float, z: Float, team: String) {
        match?.heardShot(x, z, team)
    }

    /** Leaving the room: stop running the bots (another phone takes over). */
    fun stop() {
        match?.let(::flushStats)
        match = null
        written.clear()
    }

    private fun start(me: SoloMatch.Player) {
        val teams = if (config.team.isNotEmpty()) listOf(config.team) else allTeams
        match = SoloMatch(
            city, SoloSettings(config.count, config.difficulty, allies = false, durationMs = 0L),
            me.team, allTeams.filter { it != me.team }.ifEmpty { allTeams }, label,
            startX = me.x, startZ = me.z, teams = teams,
        )
        written.clear()
        sinceWrite = WRITE_SECONDS
    }

    /** Whether [myUid] is the one to run the bots: the lowest id among the players here. */
    private fun electedIs(myUid: String): Boolean =
        (online.humans().map { it.uid } + myUid).minOrNull() == myUid

    /** Adds what's new in the bots' scores to the room's scoreboard. */
    private fun flushStats(game: SoloMatch) {
        for (s in game.stats(includeMe = false)) {
            val now = intArrayOf(s.kills, s.deaths, s.shots, s.hits, s.hitsTaken)
            val before = written[s.uid] ?: IntArray(5)
            val counts = KEYS.indices.associate { KEYS[it] to (now[it] - before[it]).coerceAtLeast(0) }
            online.addBotStats(s.uid, s.name, s.team, counts)
            written[s.uid] = now
        }
    }

    private companion object {
        val KEYS = listOf("kills", "deaths", "shots", "hits", "hitsTaken")
        const val WRITE_SECONDS = 0.15f
        const val STATS_SECONDS = 2f
        const val ELECTION_SECONDS = 1f
    }
}
