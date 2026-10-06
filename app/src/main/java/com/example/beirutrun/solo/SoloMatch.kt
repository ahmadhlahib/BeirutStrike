package com.example.beirutrun.solo

import com.example.beirutrun.city.CityMap
import com.example.beirutrun.city.Weapon
import com.example.beirutrun.online.PlayerStats
import com.example.beirutrun.online.RemotePlayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** How good the bots are: how far they see, how fast they react, how well they shoot and move. */
enum class BotDifficulty(
    val id: String,
    /** How far away they spot an enemy, metres. */
    val sight: Float,
    /** Seconds between spotting someone and the first shot. */
    val reaction: Float,
    /** Chance a shot hits: close up, and at the gun's full range (in between, a straight line). */
    val hitNear: Float,
    val hitFar: Float,
    /** Chance a hit is aimed at the head (no extra damage, but it shows). */
    val headshots: Float,
    /** Walking and running speed, m/s (the player walks at 2.6 and runs at 7.5). */
    val walk: Float,
    val run: Float,
    /** Sidesteps while shooting, rather than standing still. */
    val strafes: Boolean,
    /** Lies down to shoot at long range. */
    val crawls: Boolean,
    /** Goes looking for enemies, rather than wandering. */
    val hunts: Boolean,
    /** The guns they carry (one each, picked at random). */
    val guns: List<Weapon>,
    /** Shots in a burst with an automatic, and the pause after it, seconds. */
    val burst: IntRange,
    val pause: ClosedFloatingPointRange<Float>,
) {
    EASY("easy", sight = 40f, reaction = 1.2f, hitNear = 0.12f, hitFar = 0.03f, headshots = 0.04f,
        walk = 1.4f, run = 1.4f, strafes = false, crawls = false, hunts = false,
        guns = listOf(Weapon.M9, Weapon.GLOCK17, Weapon.AK47, Weapon.MP5), burst = 2..3, pause = 0.9f..1.6f),
    MEDIUM("medium", sight = 70f, reaction = 0.7f, hitNear = 0.22f, hitFar = 0.07f, headshots = 0.1f,
        walk = 1.5f, run = 4.5f, strafes = true, crawls = false, hunts = true,
        guns = listOf(Weapon.AK47, Weapon.M4, Weapon.MP5, Weapon.RPK), burst = 2..5, pause = 0.6f..1.2f),
    HARD("hard", sight = 110f, reaction = 0.35f, hitNear = 0.35f, hitFar = 0.12f, headshots = 0.2f,
        walk = 1.6f, run = 6f, strafes = true, crawls = true, hunts = true,
        guns = listOf(Weapon.M4, Weapon.AK47, Weapon.RPK, Weapon.SVD, Weapon.M24), burst = 3..6, pause = 0.35f..0.8f);

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/** A solo game: how many bots, how good, whether some fight on the player's team, and how long it lasts. */
data class SoloSettings(val bots: Int, val difficulty: BotDifficulty, val allies: Boolean, val durationMs: Long) {
    companion object {
        const val MIN_BOTS = 1
        const val MAX_BOTS = 8
    }
}

/**
 * A game against bots on this phone alone: nothing goes online and nothing counts toward XP.
 * The bots walk the streets, spot enemies they can see, turn, react and shoot with real guns;
 * they take hits, die, and come back 4 seconds later somewhere else, like players.
 *
 * Plain Kotlin, so whole matches can be played in unit tests. The city screen feeds it the
 * player's position ([update]) and the player's hits on bots ([hitByPlayer]); in return it gives
 * the bots as [RemotePlayer]s to draw ([players]), the shots to show and the hits on the player
 * ([Event]s), and everyone's score ([stats]).
 */
class SoloMatch(
    private val city: CityMap,
    val settings: SoloSettings,
    playerTeam: String,
    /** The other teams, for enemy bots (at least one). */
    enemyTeams: List<String>,
    /** A bot's name on screen, from its first name (e.g. "Karim · Bot"). */
    label: (String) -> String = { "$it (bot)" },
    seed: Int = Random.nextInt(),
    /** Each bot's team, in turn (an online room's); null: worked out from [settings] as in a solo game. */
    teams: List<String>? = null,
    /** Where the player starts (bots start a fair way from there). */
    startX: Float = city.spawnX,
    startZ: Float = city.spawnZ,
) {
    private val rnd = Random(seed)
    /** The streets bots walk, as a network of corners (see [StreetGraph]). */
    private val graph = StreetGraph(city)
    private var clock = 0f

    /**
     * Someone real the bots can see and shoot: [uid] ([ME] in a solo game, their player id in an
     * online room). [y] is the height of their feet.
     */
    data class Player(
        val name: String, val team: String, val x: Float, val y: Float, val z: Float,
        val prone: Boolean, val dead: Boolean, val uid: String = ME,
    )

    sealed class Event {
        /** A bot fired: show the bullet from (x, y, z) along (dx, dy, dz). */
        class Shot(val uid: String, val x: Float, val y: Float, val z: Float, val dx: Float, val dy: Float, val dz: Float, val gun: Weapon) : Event()
        /** A bot's bullet reached a player ([target]: [ME] in a solo game). */
        class PlayerHit(val fromUid: String, val fromName: String, val damage: Int, val target: String = ME) : Event()
    }

    private class Bot(val uid: String, val name: String, val team: String, var gun: Weapon) {
        // On the street network: going from corner [from] to corner [to], [t] metres along (-1: nowhere).
        var from = -1; var to = -1; var t = 0f
        /** The corners still to go through to reach corner [pathGoal], and when to plan again. */
        val path = ArrayDeque<Int>()
        var pathGoal = -1
        var nextPlan = 0f
        var x = 0f; var z = 0f
        var heading = 0f
        var speed = 0f
        var prone = false
        var health = MAX_HEALTH
        var dead = false
        var diedAt = 0f
        var killedBy = ""
        // Who they're after, when they last saw them, where, and when they may shoot.
        var target: String? = null
        var seenAt = -100f
        var seenX = 0f; var seenZ = 0f
        var fireFrom = 0f
        var nextLook = 0f
        var nextFire = 0f
        var burstLeft = 0
        var rounds = gun.magazine
        var reloadedAt = 0f
        var strafe = 1f
        var nextStrafe = 0f
        /** Where they're heading when not fighting (null: anywhere), and when to think again. */
        var goalX = Float.NaN; var goalZ = 0f
        var nextGoal = 0f
        // The last shot: how many so far, and where from and which way (for other phones to draw).
        var shotSeq = 0L
        var shotX = 0f; var shotY = 0f; var shotZ = 0f; var shotDX = 0f; var shotDY = 0f; var shotDZ = 0f
        var kills = 0; var deaths = 0; var shots = 0; var hits = 0; var hitsTaken = 0
    }

    /** Someone a bot can shoot at: a player or another bot. */
    private class Target(val uid: String, val name: String, val team: String, val x: Float, val y: Float, val z: Float, val prone: Boolean, val bot: Bot?)

    /** A bullet on its way: it lands when it would have flown that far. */
    private class Flying(val at: Float, val from: Bot, val target: String, val damage: Int)

    private val bots: List<Bot>
    private val flying = ArrayList<Flying>()
    /** The players the bots can see (in a solo game, just the one). */
    private var people: List<Player> = listOf(Player("", playerTeam, startX, 0f, startZ, prone = false, dead = false))
    /** The first player: in a solo game the only one; bots start a fair way from them. */
    private val player get() = people.first()
    private val playerTeam = playerTeam
    /** How fast each player is moving, m/s (smoothed), by id. */
    private val speeds = HashMap<String, Float>()

    // My own counts (the player's), added by the city screen as things happen (see [count]).
    private val mine = HashMap<String, Int>()

    init {
        val names = NAMES.shuffled(rnd)
        val allies = if (settings.allies) settings.bots / 2 else 0
        bots = List(settings.bots.coerceIn(SoloSettings.MIN_BOTS, SoloSettings.MAX_BOTS)) { i ->
            // Given teams (an online room's), else: with allies, half the bots (rounded down) join
            // the player and the rest are one enemy team; without, every bot is an enemy, spread
            // over the other teams.
            val team = when {
                teams != null && teams.isNotEmpty() -> teams[i % teams.size]
                i < allies -> playerTeam
                settings.allies -> enemyTeams.first()
                else -> enemyTeams[(i - allies) % enemyTeams.size]
            }
            Bot("bot$i", label(names[i % names.size]), team, settings.difficulty.guns.random(rnd)).also { place(it) }
        }
    }

    // ---- What the city screen asks -------------------------------------------------------------

    /** The bots, to draw like other players (and to send to an online room's other phones). */
    fun players(now: Long): List<RemotePlayer> = bots.map { b ->
        RemotePlayer(
            uid = b.uid, name = b.name, x = b.x, z = b.z, heading = b.heading,
            walking = b.speed > 0.2f, say = "", sayAt = 0L, faceVersion = 0L, updated = now,
            team = b.team, health = b.health.coerceAtLeast(0), dead = b.dead, killedBy = b.killedBy,
            shotSeq = b.shotSeq, shotX = b.shotX, shotY = b.shotY, shotZ = b.shotZ,
            shotDX = b.shotDX, shotDY = b.shotDY, shotDZ = b.shotDZ,
            prone = b.prone, weapon = b.gun.id, character = BOT_CHARACTER, showFace = false, bot = true,
            floor = city.groundAt(b.x, b.z),
        )
    }

    fun isBot(uid: String) = bots.any { it.uid == uid }

    fun nameOf(uid: String) = bots.firstOrNull { it.uid == uid }?.name

    /** Everyone's score, the player as [ME] (a solo game; an online room asks for the bots' alone). */
    fun stats(includeMe: Boolean = true): List<PlayerStats> = bots.map { b ->
        PlayerStats(b.uid, b.name, b.team, b.kills, b.deaths, b.shots, b.hits, b.hitsTaken)
    } + if (!includeMe) emptyList() else listOf(PlayerStats(
        ME, player.name, playerTeam, mine["kills"] ?: 0, mine["deaths"] ?: 0,
        mine["shots"] ?: 0, mine["hits"] ?: 0, mine["hitsTaken"] ?: 0,
    ))

    /** One more of the player's own [key] ("shots", "hits", "hitsTaken", "deaths" or "kills"). */
    fun count(key: String) { mine[key] = (mine[key] ?: 0) + 1 }

    /**
     * Player [from] hit bot [uid] for [damage] hearts. Returns its name if that killed it (the
     * killer's phone counts the kill), else null.
     */
    fun hitByPlayer(uid: String, damage: Int, from: String = ME): String? {
        val bot = bots.firstOrNull { it.uid == uid } ?: return null
        val shooter = people.firstOrNull { it.uid == from } ?: player
        return if (hurt(bot, damage, from, shooter.x, shooter.z)) bot.name else null
    }

    /** A player was killed by bot [uid] (its bullet took the last heart). */
    fun playerKilledBy(uid: String) { bots.firstOrNull { it.uid == uid }?.let { it.kills++ } }

    /** Someone fired from (x, z): bots in earshot not on [team] turn to look. */
    fun heardShot(x: Float, z: Float, team: String = playerTeam) {
        for (b in bots) {
            if (b.dead || b.team == team || hypot(b.x - x, b.z - z) > HEARING) continue
            if (b.target == null || clock - b.seenAt > 1f) b.heading = headingTo(b.x, b.z, x, z)
        }
    }

    // ---- The game ------------------------------------------------------------------------------

    /** Moves a solo game on by [dt] seconds; returns what happened that the screen should show. */
    fun update(dt: Float, now: Player): List<Event> = update(dt, listOf(now))

    /** Moves the game on by [dt] seconds with [now] the players; returns what happened. */
    fun update(dt: Float, now: List<Player>): List<Event> {
        clock += dt
        for (p in now) {
            val before = people.firstOrNull { it.uid == p.uid } ?: p
            // (A jump of more than 15 m is a respawn, not running.)
            val moved = hypot(p.x - before.x, p.z - before.z).let { if (it > 15f) 0f else it }
            val speed = speeds[p.uid] ?: 0f
            if (dt > 0f) speeds[p.uid] = speed + (moved / dt - speed) * (dt * 4f).coerceAtMost(1f)
        }
        if (now.isNotEmpty()) people = now
        val events = ArrayList<Event>()
        // Bullets reaching their targets.
        val landed = flying.filter { it.at <= clock }
        flying.removeAll(landed.toSet())
        for (f in landed) {
            val person = people.firstOrNull { it.uid == f.target }
            if (person != null) {
                if (!person.dead) events += Event.PlayerHit(f.from.uid, f.from.name, f.damage, person.uid)
            } else {
                if (f.from.dead) continue
                val victim = bots.firstOrNull { it.uid == f.target } ?: continue
                hurt(victim, f.damage, f.from.uid, f.from.x, f.from.z)
            }
        }
        val targets = targets()
        for (b in bots) {
            if (b.dead) {
                if (clock - b.diedAt >= RESPAWN_SECONDS) respawn(b)
                continue
            }
            think(b, dt, targets, events)
        }
        return events
    }

    private fun targets(): List<Target> {
        val list = ArrayList<Target>(bots.size + people.size)
        for (p in people) if (!p.dead) list += Target(p.uid, p.name, p.team, p.x, p.y, p.z, p.prone, null)
        for (b in bots) if (!b.dead) list += Target(b.uid, b.name, b.team, b.x, city.groundAt(b.x, b.z), b.z, b.prone, b)
        return list
    }

    private fun think(b: Bot, dt: Float, targets: List<Target>, events: MutableList<Event>) {
        val d = settings.difficulty
        // Looking round a few times a second for the nearest enemy in sight.
        if (clock >= b.nextLook) {
            b.nextLook = clock + LOOK_EVERY
            val seen = targets.filter { it.team != b.team }
                .map { it to hypot(it.x - b.x, it.z - b.z) }
                .filter { (t, dist) -> dist <= d.sight && canSee(b, t, dist) }
                .minByOrNull { it.second }?.first
            if (seen != null) {
                if (b.target != seen.uid || clock - b.seenAt > LOST_SECONDS) {
                    // Someone new: a moment to react before the first shot.
                    b.fireFrom = clock + d.reaction * (0.8f + rnd.nextFloat() * 0.4f)
                }
                b.target = seen.uid
                b.seenAt = clock
                b.seenX = seen.x; b.seenZ = seen.z
            }
        }
        val target = b.target?.let { id -> targets.firstOrNull { it.uid == id } }
        if (target == null) b.target = null
        val inSight = target != null && clock - b.seenAt <= LOOK_EVERY * 1.5f
        if (target != null && inSight) {
            // Fighting: face them, keep in range, sidestep, and shoot.
            val dist = hypot(target.x - b.x, target.z - b.z)
            turnTo(b, headingTo(b.x, b.z, target.x, target.z), dt)
            b.prone = d.crawls && dist > PRONE_RANGE && b.gun.range > PRONE_RANGE * 1.5f
            when {
                dist > b.gun.range * 0.9f -> walk(b, dt, d.run, target.x, target.z, facing = false)
                b.prone -> b.speed = 0f
                d.strafes -> strafe(b, dt)
                else -> b.speed = 0f
            }
            if (dist <= b.gun.range) shoot(b, target, dist, events)
        } else {
            b.prone = false
            if (target != null && clock - b.seenAt < LOST_SECONDS && d.hunts) {
                // Lost sight of them: go where they were last seen.
                walk(b, dt, d.run, b.seenX, b.seenZ, facing = true)
            } else {
                if (target != null && clock - b.seenAt >= LOST_SECONDS) b.target = null
                if (clock >= b.nextGoal) chooseGoal(b)
                walk(b, dt, if (d.hunts && !b.goalX.isNaN()) d.run * 0.7f else d.walk, b.goalX, b.goalZ, facing = true)
            }
        }
    }

    /**
     * Where to go when there's no one in sight: hunters head for the nearest enemy (they know
     * roughly where everyone is, as a real player soon would), others wander.
     */
    private fun chooseGoal(b: Bot) {
        b.nextGoal = clock + GOAL_SECONDS
        if (!settings.difficulty.hunts) { b.goalX = Float.NaN; return }
        val enemy = targets().filter { it.team != b.team }.minByOrNull { hypot(it.x - b.x, it.z - b.z) }
        if (enemy == null) { b.goalX = Float.NaN; return }
        // Somewhere near them, not right on them.
        b.goalX = enemy.x + (rnd.nextFloat() - 0.5f) * 30f
        b.goalZ = enemy.z + (rnd.nextFloat() - 0.5f) * 30f
    }

    /** Whether [b] can see [t] from where it stands: nothing solid between its eyes and their body. */
    private fun canSee(b: Bot, t: Target, dist: Float): Boolean {
        val eye = city.groundAt(b.x, b.z) + if (b.prone) 0.35f else 1.5f
        val ty = t.y + if (t.prone) 0.3f else 1.1f
        val steps = (dist / SIGHT_STEP).toInt()
        for (i in 1 until steps) {
            val k = i / steps.toFloat()
            val x = b.x + (t.x - b.x) * k; val y = eye + (ty - eye) * k; val z = b.z + (t.z - b.z) * k
            // A wall or a hill in the way.
            if (city.isInsideBuilding(x, y, z, 0f) || city.underground(x, y, z)) return false
        }
        return true
    }

    private fun shoot(b: Bot, t: Target, dist: Float, events: MutableList<Event>) {
        if (clock < b.fireFrom || clock < b.nextFire) return
        if (b.rounds <= 0) {
            if (clock < b.reloadedAt) return
            b.rounds = b.gun.magazine
        }
        // Not until facing them.
        if (abs(angleDiff(b.heading, headingTo(b.x, b.z, t.x, t.z))) > 0.25f) return
        val d = settings.difficulty
        // Bursts with an automatic, steady taps with anything else.
        if (b.gun.automatic) {
            if (b.burstLeft <= 0) b.burstLeft = d.burst.random(rnd)
            b.burstLeft--
            b.nextFire = clock + b.gun.fireInterval + if (b.burstLeft == 0) d.pause.start + rnd.nextFloat() * (d.pause.endInclusive - d.pause.start) else 0f
        } else {
            b.nextFire = clock + maxOf(b.gun.fireInterval * 2.2f, if (b.gun.boltAction) 1.6f else 0.35f)
        }
        b.rounds--
        if (b.rounds == 0) b.reloadedAt = clock + b.gun.reloadSeconds
        b.shots++
        var p = d.hitNear + (d.hitFar - d.hitNear) * (dist / b.gun.range).coerceIn(0f, 1f)
        if (t.prone) p *= 0.6f
        // A moving target is harder to hit, a running one much harder.
        if (t.bot == null) {
            val speed = speeds[t.uid] ?: 0f
            p *= when { speed > 5f -> 0.5f; speed > 1.5f -> 0.75f; else -> 1f }
        }
        val hit = rnd.nextFloat() < p
        // The bullet, from the muzzle towards their chest (or head), off to one side on a miss.
        val eye = city.groundAt(b.x, b.z) + if (b.prone) 0.35f else 1.45f
        val mx = b.x + sin(b.heading) * 0.6f
        val mz = b.z - cos(b.heading) * 0.6f
        val aimY = t.y + when {
            t.prone -> 0.3f
            hit && rnd.nextFloat() < d.headshots -> 1.65f
            else -> 1.15f
        }
        var dx = t.x - mx; var dy = aimY - eye; var dz = t.z - mz
        if (!hit) {
            val miss = (0.025f + rnd.nextFloat() * 0.05f) * if (rnd.nextBoolean()) 1f else -1f
            val c = cos(miss); val s = sin(miss)
            val rx = dx * c - dz * s; val rz = dx * s + dz * c
            dx = rx; dz = rz
            dy += (rnd.nextFloat() - 0.4f) * 0.04f * dist
        }
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
        events += Event.Shot(b.uid, mx, eye, mz, dx / len, dy / len, dz / len, b.gun)
        b.shotSeq++
        b.shotX = mx; b.shotY = eye; b.shotZ = mz; b.shotDX = dx / len; b.shotDY = dy / len; b.shotDZ = dz / len
        if (hit) {
            b.hits++
            flying += Flying(clock + dist / b.gun.bulletSpeed, b, t.uid, b.gun.damage)
        }
    }

    /** [b] loses [damage] hearts to [fromUid] (shooting from (fx, fz)); true if that killed it. */
    private fun hurt(b: Bot, damage: Int, fromUid: String, fx: Float, fz: Float): Boolean {
        if (b.dead) return false
        b.hitsTaken++
        b.health -= damage
        // Shot: turn to face where it came from, and fight back.
        if (b.target == null) {
            b.heading = headingTo(b.x, b.z, fx, fz)
            b.nextLook = clock
        }
        if (b.health > 0) return false
        b.dead = true
        b.diedAt = clock
        b.deaths++
        b.killedBy = fromUid
        b.speed = 0f
        b.prone = false
        b.target = null
        if (fromUid != ME) bots.firstOrNull { it.uid == fromUid }?.let { it.kills++ }
        return true
    }

    private fun respawn(b: Bot) {
        b.dead = false
        b.health = MAX_HEALTH
        b.killedBy = ""
        b.rounds = b.gun.magazine
        b.target = null
        b.nextGoal = clock
        place(b)
    }

    // ---- Moving --------------------------------------------------------------------------------

    /**
     * Puts [b] on a street corner a fair way from the player: not on top of them, not across the
     * map (40 to 150 m, if there is such a spot).
     */
    private fun place(b: Bot) {
        val all = graph.size
        if (all == 0) { b.x = city.spawnX; b.z = city.spawnZ; b.from = -1; return }
        // The corner nearest a random spot at that distance, in any direction.
        var node = -1
        for (i in 0 until 40) {
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            val r = SPAWN_AWAY + rnd.nextFloat() * (SPAWN_FAR - SPAWN_AWAY)
            val n = graph.nearest(player.x + sin(angle) * r, player.z - cos(angle) * r)
            if (n < 0 || city.isBlocked(graph.x(n), graph.z(n), 0.4f)) continue
            val d = hypot(graph.x(n) - player.x, graph.z(n) - player.z)
            if (node < 0) node = n
            if (d in SPAWN_AWAY..SPAWN_FAR) { node = n; break }
        }
        if (node < 0) node = graph.nearest(player.x, player.z).takeIf { it >= 0 } ?: rnd.nextInt(all)
        val next = graph.neighbours(node).randomOrNull(rnd) ?: node
        b.from = node; b.to = next; b.t = 0f
        b.path.clear(); b.pathGoal = -1
        b.x = graph.x(node); b.z = graph.z(node)
        b.heading = headingTo(b.x, b.z, graph.x(next), graph.z(next))
    }

    /**
     * Walks [b] along the streets at [speed]: by the shortest way to (gx, gz) if given (planned
     * over the street network, replanned every few seconds as the goal moves), else anywhere.
     * [facing]: turn to the way it walks.
     */
    private fun walk(b: Bot, dt: Float, speed: Float, gx: Float, gz: Float, facing: Boolean) {
        if (b.from < 0) { b.speed = 0f; return }
        if (gx.isNaN()) {
            b.path.clear(); b.pathGoal = -1
        } else {
            val goal = graph.nearest(gx, gz)
            if (goal != b.pathGoal || clock >= b.nextPlan) {
                b.path.clear()
                graph.route(b.to, goal)?.let { b.path.addAll(it) }
                b.pathGoal = goal
                b.nextPlan = clock + REPLAN_SECONDS
            }
        }
        b.speed = speed
        b.t += speed * dt
        var guard = 0
        while (b.t >= edgeLength(b) && guard++ < 6) {
            b.t -= edgeLength(b)
            arrive(b)
        }
        val len = edgeLength(b).coerceAtLeast(0.001f)
        val fx = (graph.x(b.to) - graph.x(b.from)) / len; val fz = (graph.z(b.to) - graph.z(b.from)) / len
        b.x = graph.x(b.from) + fx * b.t; b.z = graph.z(b.from) + fz * b.t
        if (facing && len > 0.01f) turnTo(b, atan2(fx, -fz), dt)
    }

    /** At a corner: the next one on the route, or (with none) any way but back, unless it's a dead end. */
    private fun arrive(b: Bot) {
        val at = b.to
        val ways = graph.neighbours(at)
        val next = when {
            b.path.isNotEmpty() && b.path.first() in ways -> b.path.removeFirst()
            else -> {
                b.path.clear()
                ways.filter { it != b.from }.randomOrNull(rnd) ?: b.from
            }
        }
        b.from = at; b.to = next
    }

    /** Sidesteps back and forth along the street while shooting. */
    private fun strafe(b: Bot, dt: Float) {
        if (b.from < 0) return
        if (clock >= b.nextStrafe) {
            b.nextStrafe = clock + 0.8f + rnd.nextFloat() * 0.9f
            b.strafe = if (rnd.nextFloat() < 0.25f) 0f else if (rnd.nextBoolean()) 1f else -1f
        }
        val len = edgeLength(b)
        b.t = (b.t + b.strafe * STRAFE_SPEED * dt).coerceIn(0f, len)
        b.speed = abs(b.strafe) * STRAFE_SPEED
        if (len > 0.001f) {
            b.x = graph.x(b.from) + (graph.x(b.to) - graph.x(b.from)) / len * b.t
            b.z = graph.z(b.from) + (graph.z(b.to) - graph.z(b.from)) / len * b.t
        }
    }

    private fun turnTo(b: Bot, heading: Float, dt: Float) {
        val diff = angleDiff(b.heading, heading)
        b.heading += diff.coerceIn(-TURN_SPEED * dt, TURN_SPEED * dt)
    }

    private fun edgeLength(b: Bot) = hypot(graph.x(b.to) - graph.x(b.from), graph.z(b.to) - graph.z(b.from))

    companion object {
        /** The player's id in [stats]. */
        const val ME = "me"
        const val MAX_HEALTH = 5
        const val RESPAWN_SECONDS = 4f
        /** Bots are drawn as the built-in soldier. */
        const val BOT_CHARACTER = "soldier"
        private const val LOOK_EVERY = 0.2f
        private const val LOST_SECONDS = 6f
        private const val GOAL_SECONDS = 8f
        private const val SIGHT_STEP = 1.5f
        private const val PRONE_RANGE = 45f
        private const val STRAFE_SPEED = 1.6f
        private const val TURN_SPEED = 4f
        private const val HEARING = 60f
        private const val SPAWN_AWAY = 40f
        private const val SPAWN_FAR = 150f
        private const val REPLAN_SECONDS = 3f

        /** Lebanese first names for the bots. */
        val NAMES = listOf("Karim", "Rami", "Nadim", "Ziad", "Hadi", "Fadi", "Walid", "Samir", "Tarek", "Omar", "Jad", "Elie")

        /** The game's heading (0 faces -z, turning towards +x) from (x, z) to (tx, tz). */
        fun headingTo(x: Float, z: Float, tx: Float, tz: Float) = atan2(tx - x, -(tz - z))

        /** The short way round from [a] to [b], radians. */
        fun angleDiff(a: Float, b: Float): Float {
            var d = (b - a) % (2f * PI.toFloat())
            if (d > PI) d -= 2f * PI.toFloat()
            if (d < -PI) d += 2f * PI.toFloat()
            return d
        }
    }
}
