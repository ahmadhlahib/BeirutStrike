package com.example.beirutrun.city

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Traffic and passers-by around the player: cars driving the streets in the right-hand lane,
 * turning at junctions and stopping for people and for the car in front, and people walking the
 * sidewalks and pedestrian streets, who run when there's shooting nearby.
 *
 * They are scenery: each phone makes up its own, so they aren't shared between the players in a
 * room, and bullets pass through them; cars do block the player's way. They appear out of sight
 * (beyond the haze) and are dropped once far behind, so there are always some near the player.
 *
 * Plain Kotlin: [fill] writes the models into a vertex array (position, normal, uv, as [Mesh]
 * expects), coloured by pointing into the [PALETTE] texture, so it can be unit tested.
 */
class CityLife(private val city: CityMap, private val network: RoadNetwork, seed: Int = Random.nextInt()) {

    private val rnd = Random(seed)
    private val roads = city.roads

    /** A place on the road network: going from point [from] to the next point [to] of [road], [t] metres along. */
    private open class Mover(var road: Int, var from: Int, var to: Int, var t: Float) {
        var x = 0f; var z = 0f
        /** Facing (unit, along the ground). */
        var fx = 0f; var fz = 1f
        var speed = 0f
        /** Where it's drawn: eases after the true position, so turns and lane changes are smooth. */
        var drawX = Float.NaN; var drawZ = 0f; var drawFx = 0f; var drawFz = 1f
    }

    private class Car(road: Int, from: Int, to: Int, t: Float, val model: Int, val paint: Int, val cruise: Float) :
        Mover(road, from, to, t) {
        /** Seconds spent waiting behind another car; after a while it edges on (no gridlock). */
        var waited = 0f
        var ignoreCarsFor = 0f
        /** Which loaded car model draws it (see [useCarModels]), or -1 for the built-in shapes. */
        var fit = -1
        /** Wheel turn (radians, as rolled so far) and the front wheels' steering angle (+ left). */
        var spin = 0f
        var steer = 0f
    }

    /** Half the length and width of [c]'s model, for keeping gaps and blocking the way. */
    private val Car.halfLength get() = if (fit >= 0) fits[fit].halfLength else CAR_MODELS[model].halfLength
    private val Car.halfWidth get() = if (fit >= 0) fits[fit].halfWidth else CAR_MODELS[model].halfWidth

    /**
     * The size and wheels of a loaded car model (see [useCarModels]), and how often it's picked
     * ([weight], against the others').
     */
    class CarFit(val halfLength: Float, val halfWidth: Float, val wheelRadius: Float, val wheelbase: Float, val weight: Int = 1)

    private var fits: List<CarFit> = emptyList()

    /**
     * From now on every car is drawn by one of these models (see [modelledCars]) instead of the
     * built-in shapes; cars already on the road change over too.
     */
    fun useCarModels(models: List<CarFit>) {
        fits = models.filter { it.weight > 0 }
        for (c in cars) c.fit = pickFit()
    }

    private fun pickFit(): Int {
        if (fits.isEmpty()) return -1
        var n = rnd.nextInt(fits.sumOf { it.weight })
        for ((i, f) in fits.withIndex()) { n -= f.weight; if (n < 0) return i }
        return fits.size - 1
    }

    /**
     * A car drawn with a loaded model: which one ([fit], see [useCarModels]), where, facing which
     * way (unit, along the ground), and how far its wheels have turned and its front ones steer.
     */
    class CarView(val fit: Int, val x: Float, val z: Float, val fx: Float, val fz: Float, val spin: Float, val steer: Float)

    /** The cars within [range] of (ex, ez) drawn with a loaded model. */
    fun modelledCars(ex: Float, ez: Float, range: Float): List<CarView> =
        cars.filter { it.fit >= 0 && hypot(it.drawX - ex, it.drawZ - ez) <= range }
            .map { CarView(it.fit, it.drawX, it.drawZ, it.drawFx, it.drawFz, it.spin, it.steer) }

    private class Walker(
        road: Int, from: Int, to: Int, t: Float,
        /** Which side of the road it walks on (+1 / -1), and how far into the sidewalk (0..1). */
        var side: Float, val inset: Float,
        val pace: Float, val look: IntArray, val scale: Float, val bulk: Float,
        /** Which outfit and build (see [PEOPLE]). */
        val kind: Int,
        val id: Int,
    ) : Mover(road, from, to, t) {
        var phase = 0f
        /** Standing about (chatting, waiting) until this time, in seconds of the walker's life. */
        var standUntil = 0f
        var age = 0f
        var panicUntil = -1f
        /** Which character model draws them up close, when there are any (see [Passerby.variant]). */
        val variant = (id * 7919) and 0xFFFF
        /** Times turned round to run from danger, and when (see [Passerby.turns]). */
        var turns = 0
        var turnedAt = -10f
        /** 0 alive, else how they died (see [Passerby.death]), and when. */
        var death = 0
        var diedAt = 0f
    }

    private val cars = ArrayList<Car>()
    private val walkers = ArrayList<Walker>()
    private var clock = 0f
    private var nextId = 1
    private var started = false

    /** Road segments cars and people may be placed on, by grid cell (road shl 16 or segment start). */
    private val driveCells = HashMap<Long, MutableList<Int>>()
    private val walkCells = HashMap<Long, MutableList<Int>>()

    init {
        roads.forEachIndexed { r, road ->
            val drive = drivable(r); val walk = walkable(r)
            if (!drive && !walk) return@forEachIndexed
            for (v in 0 until road.pts.size / 2 - 1) {
                val mx = (road.pts[2 * v] + road.pts[2 * v + 2]) / 2f
                val mz = (road.pts[2 * v + 1] + road.pts[2 * v + 3]) / 2f
                val key = cell(mx, mz)
                if (drive) driveCells.getOrPut(key) { ArrayList() } += (r shl 16) or v
                if (walk) walkCells.getOrPut(key) { ArrayList() } += (r shl 16) or v
            }
        }
    }

    val carCount get() = cars.size
    val walkerCount get() = walkers.size

    /**
     * A person drawn with a real character model instead (see [nearest]): who, where, facing
     * which way (the game's heading: 0 = -z), how fast, which model ([variant], any number to
     * pick one with), and their shirt and trouser colours for models that take them.
     */
    class Passerby(
        val id: Int, val x: Float, val z: Float, val heading: Float, val speed: Float,
        val variant: Int, val shirt: Int, val trousers: Int,
        /** 0 alive; else how they died: 1 falling back, 2 falling forward, 3 thrown back (Death1..3 clips). */
        val death: Int,
        /** Goes up each time they turn round to run from danger, facing [heading] from then on. */
        val turns: Int,
    )

    /** Ids of people drawn with a character model, so [fill] leaves them out. */
    var modelled: Set<Int> = emptySet()

    /** The [count] people nearest (ex, ez), within [range]. */
    fun nearest(ex: Float, ez: Float, count: Int, range: Float): List<Passerby> =
        walkers.asSequence()
            .map { it to hypot(it.drawX - ex, it.drawZ - ez) }
            .filter { it.second < range }
            .sortedBy { it.second }
            .take(count)
            .map { (w, _) ->
                Passerby(
                    w.id, w.drawX, w.drawZ, kotlin.math.atan2(w.drawFx, -w.drawFz), w.speed,
                    w.variant, PALETTE[w.look[1]], PALETTE[w.look[2]], death = w.death, turns = w.turns,
                )
            }
            .toList()

    private fun drivable(r: Int) = roads[r].kind <= CityMap.ROAD_MINOR && roads[r].width >= 4f
    private fun walkable(r: Int) = roads[r].kind <= CityMap.ROAD_PATH || roads[r].kind == CityMap.ROAD_TRACK

    // ---- Simulation ----------------------------------------------------------------------------

    /**
     * Moves everyone on by [dt] seconds around the player at ([px], [pz]); [people] holds the
     * players' positions (x, z pairs), which cars stop for.
     */
    fun update(dt: Float, px: Float, pz: Float, people: FloatArray) {
        clock += dt
        val first = !started
        started = true
        cars.removeAll { hypot(it.x - px, it.z - pz) > CAR_FORGET }
        walkers.removeAll { hypot(it.x - px, it.z - pz) > WALKER_FORGET || (it.death > 0 && clock - it.diedAt > BODY_SECONDS) }
        // At the start fill the streets all round; later only out of sight, so nobody pops up.
        val tries = if (first) 200 else 2
        repeat(tries) { if (cars.size < MAX_CARS) spawnCar(px, pz, if (first) 20f else CAR_APPEAR) }
        repeat(tries) { if (walkers.size < MAX_WALKERS) spawnWalker(px, pz, if (first) 10f else WALKER_APPEAR) }

        for (c in cars) driveCar(c, dt, people)
        for (w in walkers) walk(w, dt, px, pz)
    }

    /**
     * A bullet at (x, y, z) going along (dx, dz): if it's in someone, they're killed (and true is
     * returned, so the bullet stops). Shot from the front they fall or are thrown back, from
     * behind they fall forwards. It counts for nobody's score: these are scenery.
     */
    fun shoot(x: Float, y: Float, z: Float, dx: Float, dz: Float): Boolean {
        for (w in walkers) {
            val feet = city.groundAt(w.drawX, w.drawZ)
            if (w.death > 0 || y < feet || y > feet + PERSON_HEIGHT * w.scale) continue
            if (hypot(x - w.drawX, z - w.drawZ) > HIT_RADIUS) continue
            val fromFront = dx * w.drawFx + dz * w.drawFz < 0f
            kill(w, if (fromFront) (if (rnd.nextBoolean()) FALL_BACK else THROWN_BACK) else FALL_FORWARD)
            return true
        }
        return false
    }

    /** A grenade going off at (x, z): everyone within [radius] is killed, thrown away from it. */
    fun blast(x: Float, z: Float, radius: Float) {
        for (w in walkers) {
            if (w.death > 0 || hypot(w.drawX - x, w.drawZ - z) > radius) continue
            // Facing the blast, they're thrown back; with their back to it, they fall forwards.
            val facing = (x - w.drawX) * w.drawFx + (z - w.drawZ) * w.drawFz > 0f
            kill(w, if (facing) THROWN_BACK else FALL_FORWARD)
        }
        alarm(x, z)
    }

    private fun kill(w: Walker, how: Int) {
        w.death = how
        w.diedAt = clock
        w.speed = 0f
        alarm(w.drawX, w.drawZ)
    }

    /** A shot or blast at (x, z): people near it run. */
    fun alarm(x: Float, z: Float) {
        for (w in walkers) {
            if (w.death > 0 || hypot(w.x - x, w.z - z) > PANIC_RADIUS) continue
            if (w.panicUntil < clock) {
                // Turn away if heading towards the danger.
                if (w.fx * (x - w.x) + w.fz * (z - w.z) > 0f) {
                    val f = w.from; w.from = w.to; w.to = f; w.t = segLength(w) - w.t
                    // Facing the new way at once: a character model turns itself round (see Passerby.turns).
                    w.drawFx = -w.drawFx; w.drawFz = -w.drawFz
                    w.turns++
                    w.turnedAt = clock
                }
            }
            w.panicUntil = clock + PANIC_SECONDS
            w.standUntil = 0f
        }
    }

    /** True when a circle of radius [r] at (x, z) overlaps a car (the player can't walk through one). */
    fun blocks(x: Float, z: Float, r: Float): Boolean {
        for (c in cars) {
            val dx = x - c.x; val dz = z - c.z
            val along = dx * c.fx + dz * c.fz
            val across = -dx * c.fz + dz * c.fx
            if (abs(along) < c.halfLength + r && abs(across) < c.halfWidth + r) return true
        }
        return false
    }

    private fun spawnCar(px: Float, pz: Float, minDistance: Float) {
        val pick = pickSegment(driveCells, px, pz, minDistance, CAR_SPAWN_MAX) ?: return
        val r = pick shr 16; val v = pick and 0xFFFF
        val forward = rnd.nextBoolean()
        val car = Car(
            r, if (forward) v else v + 1, if (forward) v + 1 else v, 0f,
            model = CAR_MIX[rnd.nextInt(CAR_MIX.size)], paint = CAR_PAINTS[rnd.nextInt(CAR_PAINTS.size)],
            cruise = when (roads[r].kind) { CityMap.ROAD_MAJOR -> 13f; CityMap.ROAD_MEDIUM -> 11f; else -> 8f } * (0.8f + rnd.nextFloat() * 0.3f),
        )
        car.t = rnd.nextFloat() * segLength(car)
        place(car, laneOffset(car))
        if (cars.any { hypot(it.x - car.x, it.z - car.z) < 12f }) return
        if (city.isInsideBuilding(car.x, city.groundAt(car.x, car.z) + 1f, car.z, 1f)) return
        car.speed = car.cruise * 0.7f
        car.fit = pickFit()
        cars += car
    }

    private fun spawnWalker(px: Float, pz: Float, minDistance: Float) {
        val pick = pickSegment(walkCells, px, pz, minDistance, WALKER_SPAWN_MAX) ?: return
        val r = pick shr 16; val v = pick and 0xFFFF
        val forward = rnd.nextBoolean()
        val kind = rnd.nextInt(PEOPLE.size)
        // Colours for skin, top, trousers or skirt, shoes, hair, jacket/scarf/dress, and bag.
        val look = intArrayOf(
            SKINS[rnd.nextInt(SKINS.size)], SHIRTS[rnd.nextInt(SHIRTS.size)], PANTS[rnd.nextInt(PANTS.size)],
            SHOES[rnd.nextInt(SHOES.size)], HAIR[rnd.nextInt(HAIR.size)], OUTER[rnd.nextInt(OUTER.size)],
            BAGS[rnd.nextInt(BAGS.size)],
        )
        val w = Walker(
            r, if (forward) v else v + 1, if (forward) v + 1 else v, 0f,
            side = if (rnd.nextBoolean()) 1f else -1f, inset = 0.25f + rnd.nextFloat() * 0.5f,
            pace = 1.1f + rnd.nextFloat() * 0.45f, look = look,
            scale = (0.93f + rnd.nextFloat() * 0.13f) * PEOPLE[kind].height, bulk = 0.9f + rnd.nextFloat() * 0.22f, kind = kind, id = nextId++,
        )
        w.t = rnd.nextFloat() * segLength(w)
        w.phase = rnd.nextFloat() * 6.28f
        if (rnd.nextFloat() < 0.18f) w.standUntil = 4f + rnd.nextFloat() * 20f
        place(w, walkOffset(w))
        if (!clear(w.x, w.z)) return
        walkers += w
    }

    /** A random segment from [cells] whose middle is between [near] and [far] metres from (px, pz). */
    private fun pickSegment(cells: HashMap<Long, MutableList<Int>>, px: Float, pz: Float, near: Float, far: Float): Int? {
        val reach = (far / CELL).toInt() + 1
        val cx = floor(px / CELL).toInt(); val cz = floor(pz / CELL).toInt()
        repeat(6) {
            val list = cells[key(cx + rnd.nextInt(-reach, reach + 1), cz + rnd.nextInt(-reach, reach + 1))] ?: return@repeat
            val pick = list[rnd.nextInt(list.size)]
            val road = roads[pick shr 16]; val v = pick and 0xFFFF
            val mx = (road.pts[2 * v] + road.pts[2 * v + 2]) / 2f
            val mz = (road.pts[2 * v + 1] + road.pts[2 * v + 3]) / 2f
            val d = hypot(mx - px, mz - pz)
            if (d in near..far && city.inPlayArea(mx, mz)) return pick
        }
        return null
    }

    private fun driveCar(c: Car, dt: Float, people: FloatArray) {
        // How fast it wants to go: slower into a junction, and not into whatever is ahead.
        var target = c.cruise
        val left = segLength(c) - c.t
        if (left < 10f && network.at(px(c.road, c.to), pz(c.road, c.to)).size > 1) target = min(target, 4f + left * 0.6f)
        c.ignoreCarsFor -= dt
        var blockedByCar = false
        if (c.ignoreCarsFor <= 0f) for (o in cars) {
            if (o === c) continue
            val gap = gapAhead(c, o.x, o.z, 2f) - o.halfLength - c.halfLength
            if (gap < Float.MAX_VALUE / 2) {
                val allowed = max(0f, (gap - 2f) * 1.1f)
                if (allowed < target) { target = allowed; blockedByCar = allowed < 0.5f }
            }
        }
        for (w in walkers) if (w.death == 0) target = min(target, max(0f, (gapAhead(c, w.x, w.z, 1.4f) - c.halfLength - 2f) * 1.2f))
        var i = 0
        while (i + 1 < people.size) {
            target = min(target, max(0f, (gapAhead(c, people[i], people[i + 1], 1.6f) - c.halfLength - 2.5f) * 1.2f))
            i += 2
        }
        if (blockedByCar && c.speed < 0.3f) {
            c.waited += dt
            if (c.waited > 6f) { c.ignoreCarsFor = 2.5f; c.waited = 0f }
        } else c.waited = 0f

        c.speed = if (target > c.speed) min(target, c.speed + 3f * dt) else max(target, c.speed - 9f * dt)
        advance(c, c.speed * dt) { r -> drivable(r) }
        place(c, laneOffset(c))
        val oldFx = c.drawFx; val oldFz = c.drawFz
        ease(c, dt, 5f)
        turnWheels(c, dt, oldFx, oldFz)
    }

    /**
     * Rolls [c]'s wheels on by the distance it went, and steers the front ones into the turn it
     * is making (it faced (oldFx, oldFz) before this step): the angle that would drive its
     * wheelbase round the curve at this speed, eased so the wheel doesn't flick.
     */
    private fun turnWheels(c: Car, dt: Float, oldFx: Float, oldFz: Float) {
        if (dt <= 0f) return
        val radius = if (c.fit >= 0) fits[c.fit].wheelRadius else 0.32f
        val wheelbase = if (c.fit >= 0) fits[c.fit].wheelbase else 2.8f
        c.spin = (c.spin + c.speed * dt / radius) % (2f * PI.toFloat())
        // How far it turned towards its left, (oldFz, -oldFx): + is a left turn.
        val turn = kotlin.math.asin((c.drawFx * oldFz - c.drawFz * oldFx).coerceIn(-1f, 1f))
        val target = if (c.speed > 0.5f) kotlin.math.atan(wheelbase * (turn / dt) / c.speed).coerceIn(-MAX_STEER, MAX_STEER) else c.steer
        c.steer += (target - c.steer) * min(1f, dt * 6f)
    }

    /** How far ahead of [c] the point (x, z) is, if it's in its way (within [width] of its path); else huge. */
    private fun gapAhead(c: Mover, x: Float, z: Float, width: Float): Float {
        val dx = x - c.x; val dz = z - c.z
        val along = dx * c.fx + dz * c.fz
        if (along <= 0f || along > 16f) return Float.MAX_VALUE
        val across = -dx * c.fz + dz * c.fx
        return if (abs(across) < width) along else Float.MAX_VALUE
    }

    private fun walk(w: Walker, dt: Float, px: Float, pz: Float) {
        w.age += dt
        if (w.death > 0) { w.speed = 0f; return }
        val panic = w.panicUntil > clock
        var speed = when {
            // Turning round, they speed up into the run rather than slide away mid-turn.
            panic -> w.pace * 3f * ((clock - w.turnedAt) / TURN_SECONDS).coerceIn(0.15f, 1f)
            w.age < w.standUntil -> 0f
            else -> w.pace
        }
        // Wait for the player to get out of the way rather than walk through them.
        if (gapAhead(w, px, pz, 0.6f) < 1.2f) speed = 0f
        w.speed = speed
        w.phase += speed * dt * 2.6f
        if (speed > 0f) {
            val (oldX, oldZ) = w.x to w.z
            val oldRoad = w.road; val oldFrom = w.from; val oldTo = w.to; val oldT = w.t
            advance(w, speed * dt) { r -> walkable(r) }
            if (w.road != oldRoad) w.side = if (rnd.nextBoolean()) 1f else -1f
            place(w, walkOffset(w))
            if (!clear(w.x, w.z)) {
                // Something in the way (a wall where the sidewalk runs out): turn round.
                w.road = oldRoad; w.from = oldTo; w.to = oldFrom
                w.t = segLength(w) - oldT
                place(w, walkOffset(w))
                if (!clear(w.x, w.z)) { w.x = oldX; w.z = oldZ }
            }
        }
        ease(w, dt, 6f)
    }

    private fun clear(x: Float, z: Float) = city.inPlayArea(x, z) && !city.isInsideBuilding(x, city.groundAt(x, z) + 1f, z, 0.3f)

    /**
     * Moves [m] [distance] metres along its road; at the end of a segment it carries on along
     * the road, or at a junction turns onto another road [allowed] for it (most often straight
     * on), or at a dead end turns back.
     */
    private inline fun advance(m: Mover, distance: Float, allowed: (Int) -> Boolean) {
        m.t += distance
        var guard = 0
        while (m.t >= segLength(m) && guard++ < 8) {
            m.t -= segLength(m)
            val road = m.road; val at = m.to; val dir = m.to - m.from
            val n = roads[road].pts.size / 2
            val options = ArrayList<IntArray>(4)
            for (s in network.at(px(road, at), pz(road, at))) {
                if (!allowed(s.road)) continue
                val count = roads[s.road].pts.size / 2
                for (d in intArrayOf(1, -1)) {
                    if (s.vertex + d !in 0 until count) continue
                    if (s.road == road && s.vertex == at && d == -dir) continue
                    // Straight on is more likely than a turn.
                    val weight = if (s.road == road && d == dir) 3 else 1
                    repeat(weight) { options += intArrayOf(s.road, s.vertex, s.vertex + d) }
                }
            }
            if (options.isEmpty() && at + dir in 0 until n) options += intArrayOf(road, at, at + dir)
            val next = if (options.isEmpty()) intArrayOf(road, at, at - dir) else options[rnd.nextInt(options.size)]
            m.road = next[0]; m.from = next[1]; m.to = next[2]
        }
    }

    /** Puts [m] on its segment, [offset] metres to the right of the way it's going. */
    private fun place(m: Mover, offset: Float) {
        val ax = px(m.road, m.from); val az = pz(m.road, m.from)
        val bx = px(m.road, m.to); val bz = pz(m.road, m.to)
        val len = hypot(bx - ax, bz - az)
        if (len < 1e-3f) { m.x = ax; m.z = az; return }
        m.fx = (bx - ax) / len; m.fz = (bz - az) / len
        // The right-hand side of (fx, fz), with x east and z south, is (-fz, fx).
        m.x = ax + m.fx * m.t - m.fz * offset
        m.z = az + m.fz * m.t + m.fx * offset
        if (m.drawX.isNaN()) { m.drawX = m.x; m.drawZ = m.z; m.drawFx = m.fx; m.drawFz = m.fz }
    }

    private fun ease(m: Mover, dt: Float, rate: Float) {
        val k = min(1f, dt * rate)
        m.drawX += (m.x - m.drawX) * k
        m.drawZ += (m.z - m.drawZ) * k
        var fx = m.drawFx + (m.fx - m.drawFx) * k
        var fz = m.drawFz + (m.fz - m.drawFz) * k
        val l = hypot(fx, fz)
        if (l > 1e-3f) { fx /= l; fz /= l } else { fx = m.fx; fz = m.fz }
        m.drawFx = fx; m.drawFz = fz
    }

    /** Right-hand traffic: half a carriageway's width out, less on narrow streets. */
    private fun laneOffset(c: Car) = (roads[c.road].width / 4f).coerceIn(1.1f, 3.2f)

    /** On the sidewalk of a road with traffic; anywhere across a pedestrian street. */
    private fun walkOffset(w: Walker): Float {
        val road = roads[w.road]
        val half = road.width / 2f
        val sidewalk = CityScene.sidewalkWidth(road.kind)
        // The side is fixed to the road's own direction, whichever way the walker goes along it.
        val sign = if (w.to > w.from) w.side else -w.side
        return sign * if (sidewalk > 0f) half + 0.3f + (sidewalk - 0.6f) * w.inset else half * 0.8f * (w.inset - 0.5f) * 2f
    }

    private fun segLength(m: Mover) = hypot(px(m.road, m.to) - px(m.road, m.from), pz(m.road, m.to) - pz(m.road, m.from))
    private fun px(road: Int, v: Int) = roads[road].pts[2 * v]
    private fun pz(road: Int, v: Int) = roads[road].pts[2 * v + 1]

    private fun cell(x: Float, z: Float) = key(floor(x / CELL).toInt(), floor(z / CELL).toInt())
    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) xor (cz.toLong() and 0xFFFFFFFFL)

    // ---- Drawing -------------------------------------------------------------------------------

    private var out = FloatArray(32 * 1024)
    private var size = 0

    /** How many of the floats [fill] wrote last are cars (they come first; people follow). */
    var carFloats = 0
        private set

    /**
     * Writes every car within [carRange] and person within [walkerRange] of (ex, ez) as
     * triangles; returns the array and how many floats of it are used. Cars beyond
     * [CAR_DETAIL_RANGE] get their lighter model.
     */
    fun fill(ex: Float, ez: Float, carRange: Float, walkerRange: Float): Pair<FloatArray, Int> {
        size = 0
        for (c in cars) {
            val d = hypot(c.drawX - ex, c.drawZ - ez)
            if (d > carRange || c.fit >= 0) continue
            val m = if (d < CAR_DETAIL_RANGE) CAR_MODELS[c.model] else CAR_MODELS_FAR[c.model]
            emit(m.floats, m.roles, c.drawX, city.groundAt(c.drawX, c.drawZ), c.drawZ, c.drawFx, c.drawFz) { role -> if (role == PAINT) c.paint else role }
        }
        carFloats = size
        for (w in walkers) {
            val d = hypot(w.drawX - ex, w.drawZ - ez)
            if (d > walkerRange || w.id in modelled) continue
            person(w, detail = d < PERSON_DETAIL_RANGE)
        }
        return out to size
    }

    /** Copies a model's triangles in, turned to face (fx, fz) and moved to (x, y, z); [color] maps a part's role to a palette entry. */
    private inline fun emit(
        floats: FloatArray, roles: IntArray, x: Float, y: Float, z: Float, fx: Float, fz: Float, color: (Int) -> Int,
    ) {
        ensure(floats.size / 6 * 8)
        // Model +z is forward; model +x maps to (fz, -fx), which keeps the turn a rotation.
        val ax = fz; val az = -fx
        var v = 0
        while (v < roles.size) {
            val i = v * 6
            val lx = floats[i]; val ly = floats[i + 1]; val lz = floats[i + 2]
            val nx = floats[i + 3]; val ny = floats[i + 4]; val nz = floats[i + 5]
            val c = color(roles[v])
            put(x + lx * ax + lz * fx, y + ly, z + lx * az + lz * fz, nx * ax + nz * fx, ny, nx * az + nz * fz, c)
            v++
        }
    }

    /**
     * One person, posed from their walking [Walker.phase]: legs swinging from the hips with the
     * knee bending as each comes through, arms swinging with the opposite leg, the body bobbing
     * and shifting over the foot it stands on, leaning into a run; standing, just breathing.
     */
    private fun person(w: Walker, detail: Boolean) {
        // Where they stand (a hillside or a road), once for the whole body.
        val ground = city.groundAt(w.drawX, w.drawZ)
        val moving = w.speed > 0.05f
        val run = w.speed > w.pace * 1.5f
        val swing = if (!moving) 0f else if (run) 0.8f else 0.38f + 0.08f * (w.speed - 1.1f)
        val s = sin(w.phase)
        val bob = if (moving) abs(s) * (if (run) 0.05f else 0.02f) else sin(w.age * 1.7f) * 0.004f
        val sway = if (moving) s * (if (run) 0.01f else 0.016f) else 0f
        val lean = if (run) 0.17f else if (moving) 0.04f else 0f
        val cLean = cos(lean); val sLean = sin(lean)
        val ax = w.drawFz; val az = -w.drawFx
        // Shot: falls over in the first moments, backwards or (shot from behind) forwards.
        val fall = if (w.death > 0) ((clock - w.diedAt) / FALL_SECONDS).coerceIn(0f, 1f) else 0f
        val fallAngle = fall * PI.toFloat() / 2f * if (w.death == FALL_FORWARD) 1f else -1f
        val cFall = cos(fallAngle); val sFall = sin(fallAngle)
        val parts = (if (detail) PEOPLE_NEAR else PEOPLE_FAR)[w.kind]
        for (part in parts) {
            // A negative turn swings a limb forward; each arm's side is flipped, so it swings
            // with the opposite leg.
            val legPhase = w.phase + if (part.side > 0f) 0f else PI.toFloat()
            var upper = 0f; var lower = 0f
            when (part.limb) {
                LEG_UPPER -> upper = -sin(legPhase) * swing
                LEG_LOWER -> { upper = -sin(legPhase) * swing; lower = max(0f, cos(legPhase)) * swing * 1.4f }
                ARM_UPPER -> upper = -sin(legPhase) * swing * 0.75f - (if (run) 0.15f else 0f)
                ARM_LOWER -> {
                    upper = -sin(legPhase) * swing * 0.75f - (if (run) 0.15f else 0f)
                    lower = if (run) -1.25f else -0.18f - max(0f, sin(legPhase)) * swing * 0.5f
                }
            }
            val cu = cos(upper); val su = sin(upper); val cl = cos(lower); val sl = sin(lower)
            ensure(part.roles.size * 8)
            val f = part.floats
            for (v in part.roles.indices) {
                val i = v * 6
                var ly = f[i + 1]; var lz = f[i + 2]
                var ny = f[i + 4]; var nz = f[i + 5]
                if (lower != 0f) {
                    // About the knee or elbow (the x axis): + swings the far end backwards.
                    val dy = ly - part.lowerPivot
                    val ry = dy * cl - lz * sl; val rz = dy * sl + lz * cl
                    ly = ry + part.lowerPivot; lz = rz
                    val rny = ny * cl - nz * sl; val rnz = ny * sl + nz * cl
                    ny = rny; nz = rnz
                }
                if (upper != 0f) {
                    val dy = ly - part.pivot
                    val ry = dy * cu - lz * su; val rz = dy * su + lz * cu
                    ly = ry + part.pivot; lz = rz
                    val rny = ny * cu - nz * su; val rnz = ny * su + nz * cu
                    ny = rny; nz = rnz
                }
                if (part.upperBody && lean != 0f) {
                    // Leaning forward from the hips: + tips the top forwards.
                    val dy = ly - HIP_Y
                    val ry = dy * cLean - lz * sLean; val rz = dy * sLean + lz * cLean
                    ly = ry + HIP_Y; lz = rz
                    val rny = ny * cLean - nz * sLean; val rnz = ny * sLean + nz * cLean
                    ny = rny; nz = rnz
                }
                val lx = f[i] * w.bulk + sway
                ly = ly * w.scale + bob; lz *= w.bulk
                if (fall > 0f) {
                    // Fallen: the whole body tipped over at the feet (+ forwards), resting on the ground.
                    val ry = ly * cFall - lz * sFall; val rz = ly * sFall + lz * cFall
                    ly = ry + fall * 0.11f; lz = rz
                    val rny = ny * cFall - nz * sFall; val rnz = ny * sFall + nz * cFall
                    ny = rny; nz = rnz
                }
                val nx = f[i + 3]
                val color = when (val role = part.roles[v]) {
                    SKIN -> w.look[0]; SHIRT -> w.look[1]; LEGS -> w.look[2]; FEET -> w.look[3]
                    HAIR_ROLE -> w.look[4]; OUTER_ROLE -> w.look[5]; BAG_ROLE -> w.look[6]
                    else -> role
                }
                put(
                    w.drawX + lx * ax + lz * w.drawFx, ly + ground, w.drawZ + lx * az + lz * w.drawFz,
                    nx * ax + nz * w.drawFx, ny, nx * az + nz * w.drawFz, color,
                )
            }
        }
    }

    private fun ensure(vertices: Int) {
        if (size + vertices * 8 > out.size) out = out.copyOf(max(out.size * 2, size + vertices * 8))
    }

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Int) {
        val o = out
        o[size] = x; o[size + 1] = y; o[size + 2] = z
        o[size + 3] = nx; o[size + 4] = ny; o[size + 5] = nz
        o[size + 6] = ((color % 8) + 0.5f) / 8f; o[size + 7] = ((color / 8) + 0.5f) / 8f
        size += 8
    }

    // ---- Models --------------------------------------------------------------------------------

    /**
     * A cross-section of a car at [z]: a box from [yb] to [yt], [hwb] wide each side at the
     * bottom and [hwt] at the top (cars narrow towards the roof), its corners rounded by [rb]
     * below and [rt] above. [span] says what the stretch to the next section is (cabins only).
     */
    private class Section(
        val z: Float, val yb: Float, val yt: Float, val hwb: Float, val hwt: Float, val rb: Float, val rt: Float, val span: Int = 0,
    )

    /** Triangles in model space (x, y, z, nx, ny, nz per vertex) with a colour role for each vertex. */
    private class Model(val floats: FloatArray, val roles: IntArray, val halfLength: Float = 0f, val halfWidth: Float = 0f)

    /** A part of a person: rigid, or a limb turning about [pivot] (and its lower half about [lowerPivot]). */
    private class Part(
        val floats: FloatArray, val roles: IntArray, val limb: Int = 0, val side: Float = 1f,
        val pivot: Float = 0f, val lowerPivot: Float = 0f,
        /** Above the hips: leans with the body when running. */
        val upperBody: Boolean = false,
    )

    /** Collects triangles for a model. */
    private class Shape {
        val f = ArrayList<Float>()
        val roles = ArrayList<Int>()

        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, role: Int) {
            f += x; f += y; f += z; f += nx; f += ny; f += nz; roles += role
        }

        /** A triangle, its normal from its corners. */
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, role: Int, flipToward: FloatArray? = null) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l < 1e-7f) return
            nx /= l; ny /= l; nz /= l
            // Normals point away from [flipToward] (the shape's middle).
            if (flipToward != null) {
                val mx = (a[0] + b[0] + c[0]) / 3f - flipToward[0]
                val my = (a[1] + b[1] + c[1]) / 3f - flipToward[1]
                val mz = (a[2] + b[2] + c[2]) / 3f - flipToward[2]
                if (nx * mx + ny * my + nz * mz < 0f) {
                    vertex(a[0], a[1], a[2], -nx, -ny, -nz, role); vertex(c[0], c[1], c[2], -nx, -ny, -nz, role); vertex(b[0], b[1], b[2], -nx, -ny, -nz, role)
                    return
                }
            }
            vertex(a[0], a[1], a[2], nx, ny, nz, role); vertex(b[0], b[1], b[2], nx, ny, nz, role); vertex(c[0], c[1], c[2], nx, ny, nz, role)
        }

        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, role: Int, flipToward: FloatArray? = null) {
            tri(a, b, c, role, flipToward); tri(a, c, d, role, flipToward)
        }


        /** Points round a [Section], [k] on each rounded corner, starting under the +x side and going up it. */
        fun ring(s: Section, k: Int): FloatArray {
            val h = s.yt - s.yb
            val rt = min(s.rt, h * 0.45f); val rb = min(s.rb, h * 0.3f)
            val corners = arrayOf(
                floatArrayOf(s.hwb - rb, s.yb + rb, rb), floatArrayOf(s.hwt - rt, s.yt - rt, rt),
                floatArrayOf(-(s.hwt - rt), s.yt - rt, rt), floatArrayOf(-(s.hwb - rb), s.yb + rb, rb),
            )
            val out = FloatArray(8 * k)
            var n = 0
            corners.forEachIndexed { c, corner ->
                for (j in 0 until k) {
                    val a = Math.toRadians(ringAngle(c * k + j, k).toDouble())
                    out[n++] = corner[0] + cos(a).toFloat() * corner[2]
                    out[n++] = corner[1] + sin(a).toFloat() * corner[2]
                }
            }
            return out
        }

        /**
         * A smooth solid through [sections] (ordered along z), capped at both ends with [capRole].
         * [role] colours each face from which part of the ring it is ([BOTTOM], [SIDE] or [TOP]),
         * the section it starts from and its mean height.
         */
        fun loft(sections: List<Section>, k: Int, capRole: Int, role: (part: Int, from: Section, y: Float) -> Int) {
            val m = 4 * k
            val rings = sections.map { ring(it, k) }
            fun p(i: Int, j: Int) = floatArrayOf(rings[i][2 * (j % m)], rings[i][2 * (j % m) + 1], sections[i].z)
            // Smooth normals: across the ring crossed with along the body, turned outwards.
            val normals = Array(sections.size) { i ->
                Array(m) { j ->
                    val a = p(i, j + m - 1); val b = p(i, j + 1)
                    val f = p(min(i + 1, sections.size - 1), j); val r = p(max(i - 1, 0), j)
                    val tx = b[0] - a[0]; val ty = b[1] - a[1]; val tz = b[2] - a[2]
                    val sx = f[0] - r[0]; val sy = f[1] - r[1]; val sz = f[2] - r[2]
                    var nx = ty * sz - tz * sy; var ny = tz * sx - tx * sz; var nz = tx * sy - ty * sx
                    val c = p(i, j)
                    val out = nx * c[0] + ny * (c[1] - (sections[i].yb + sections[i].yt) / 2f)
                    if (out < 0f) { nx = -nx; ny = -ny; nz = -nz }
                    val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                    floatArrayOf(nx / l, ny / l, nz / l)
                }
            }
            fun v(i: Int, j: Int, role: Int) {
                val q = p(i, j); val n = normals[i][j % m]
                vertex(q[0], q[1], q[2], n[0], n[1], n[2], role)
            }
            for (i in 0 until sections.size - 1) for (j in 0 until m) {
                val y = (rings[i][2 * j + 1] + rings[i][2 * ((j + 1) % m) + 1]) / 2f
                val r = role(ringPart(j, k), sections[i], y)
                v(i, j, r); v(i, j + 1, r); v(i + 1, j + 1, r)
                v(i, j, r); v(i + 1, j + 1, r); v(i + 1, j, r)
            }
            for (i in intArrayOf(0, sections.size - 1)) {
                val s = sections[i]
                val outward = if (i == 0) sign(s.z - sections[1].z) else sign(s.z - sections[i - 1].z)
                val centre = floatArrayOf(0f, (s.yb + s.yt) / 2f, s.z)
                for (j in 0 until m) {
                    val a = p(i, j); val b = p(i, j + 1)
                    vertex(centre[0], centre[1], centre[2], 0f, 0f, outward, capRole)
                    vertex(a[0], a[1], a[2], 0f, 0f, outward, capRole)
                    vertex(b[0], b[1], b[2], 0f, 0f, outward, capRole)
                }
            }
        }

        /**
         * A wheel on the x axis at (x, y, z): tread and tyre wall, a spoked rim (alternating
         * bright and dark sectors) and a dark hub; [sides] round.
         */
        fun wheel(x: Float, y: Float, z: Float, r: Float, width: Float, sides: Int, spokes: Boolean) {
            val out = sign(x)
            val face = x + out * width / 2f
            val mid = floatArrayOf(x - out * width, y, z)
            fun at(a: Float, px: Float, rr: Float) = floatArrayOf(px, y + sin(a) * rr, z + cos(a) * rr)
            for (k in 0 until sides) {
                val a0 = 2f * PI.toFloat() * k / sides; val a1 = 2f * PI.toFloat() * (k + 1) / sides
                quad(at(a0, x - out * width / 2f, r), at(a1, x - out * width / 2f, r), at(a1, face, r), at(a0, face, r), TYRE, mid)
                quad(at(a0, face, r), at(a1, face, r), at(a1, face, r * 0.68f), at(a0, face, r * 0.68f), TYRE, mid)
                val rimFace = face + out * 0.004f
                val rim = if (!spokes || k % 2 == 0) CHROME else SPOKE
                quad(at(a0, rimFace, r * 0.68f), at(a1, rimFace, r * 0.68f), at(a1, rimFace, r * 0.22f), at(a0, rimFace, r * 0.22f), rim, mid)
                tri(floatArrayOf(rimFace, y, z), at(a0, rimFace, r * 0.22f), at(a1, rimFace, r * 0.22f), TRIM, mid)
            }
        }

        /** A box between two corners. */
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, role: Int) {
            val c = floatArrayOf((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f)
            fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
            quad(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), role, c)
            quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0), role, c)
            quad(p(x1, y0, z0), p(x1, y0, z1), p(x1, y1, z1), p(x1, y1, z0), role, c)
            quad(p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0), role, c)
            quad(p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1), role, c)
        }

        /** A flat panel across the car at [z], facing away from its middle. */
        fun panelZ(z: Float, x0: Float, x1: Float, y0: Float, y1: Float, role: Int) {
            val inner = floatArrayOf((x0 + x1) / 2f, (y0 + y1) / 2f, z - sign(z))
            quad(floatArrayOf(x0, y0, z), floatArrayOf(x1, y0, z), floatArrayOf(x1, y1, z), floatArrayOf(x0, y1, z), role, inner)
        }

        /** A flat panel on the car's side at [x]. */
        fun panelX(x: Float, z0: Float, z1: Float, y0: Float, y1: Float, role: Int) {
            val inner = floatArrayOf(0f, (y0 + y1) / 2f, (z0 + z1) / 2f)
            quad(floatArrayOf(x, y0, z0), floatArrayOf(x, y0, z1), floatArrayOf(x, y1, z1), floatArrayOf(x, y1, z0), role, inner)
        }

        /** The dark wheel arch on the car's side at [x]: a half disc over the wheel at (y, z). */
        fun arch(x: Float, y: Float, z: Float, r: Float) {
            val inner = floatArrayOf(0f, y, z)
            val n = 7
            for (k in 0 until n) {
                val a0 = PI.toFloat() * k / n; val a1 = PI.toFloat() * (k + 1) / n
                tri(floatArrayOf(x, y, z), floatArrayOf(x, y + sin(a0) * r, z + cos(a0) * r), floatArrayOf(x, y + sin(a1) * r, z + cos(a1) * r), TRIM, inner)
            }
        }

        /** A smooth ellipsoid (or its top half), for heads and hair. */
        fun blob(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, role: Int, top: Boolean = false, stacks: Int = 4, slices: Int = 7) {
            val start = if (top) 0.0 else -PI / 2
            fun v(i: Int, j: Int) {
                val lat = start + (PI / 2 - start) * i / stacks
                val lon = 2 * PI * j / slices
                val x = (cos(lat) * cos(lon)).toFloat(); val y = sin(lat).toFloat(); val z = (cos(lat) * sin(lon)).toFloat()
                vertex(cx + x * rx, cy + y * ry, cz + z * rz, x, y, z, role)
            }
            for (i in 0 until stacks) for (j in 0 until slices) {
                v(i, j); v(i + 1, j); v(i + 1, j + 1)
                v(i, j); v(i + 1, j + 1); v(i, j + 1)
            }
        }

        /**
         * A smooth upright solid round (cx, cz) through [rings], bottom to top, each
         * (y, half width, half depth, forward offset): a limb, a body, a head. [role] colours
         * each stretch by its middle height; [hole] leaves out sides of a stretch, given its
         * height and how far forward the side faces (1: straight ahead), as for a headscarf's opening.
         */
        fun column(
            cx: Float, cz: Float, rings: List<FloatArray>, sides: Int, role: (Float) -> Int,
            bottomCap: Boolean = false, topCap: Boolean = false, hole: ((Float, Float) -> Boolean)? = null,
        ) {
            val n = rings.size
            fun p(i: Int, k: Int): FloatArray {
                val r = rings[i]
                val a = 2f * PI.toFloat() * ((k % sides + sides) % sides) / sides
                return floatArrayOf(cx + cos(a) * r[1], r[0], cz + r[3] + sin(a) * r[2])
            }
            val normals = Array(n) { i ->
                Array(sides) { k ->
                    val a = p(i, k - 1); val b = p(i, k + 1)
                    val u = p(min(i + 1, n - 1), k); val d = p(max(i - 1, 0), k)
                    val tx = b[0] - a[0]; val ty = b[1] - a[1]; val tz = b[2] - a[2]
                    val sx = u[0] - d[0]; val sy = u[1] - d[1]; val sz = u[2] - d[2]
                    var nx = ty * sz - tz * sy; var ny = tz * sx - tx * sz; var nz = tx * sy - ty * sx
                    val c = p(i, k)
                    if (nx * (c[0] - cx) + nz * (c[2] - cz - rings[i][3]) < 0f) { nx = -nx; ny = -ny; nz = -nz }
                    val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                    floatArrayOf(nx / l, ny / l, nz / l)
                }
            }
            fun v(i: Int, k: Int, r: Int) {
                val q = p(i, k); val nn = normals[i][(k % sides + sides) % sides]
                vertex(q[0], q[1], q[2], nn[0], nn[1], nn[2], r)
            }
            for (i in 0 until n - 1) {
                val y = (rings[i][0] + rings[i + 1][0]) / 2f
                val r = role(y)
                for (k in 0 until sides) {
                    if (hole?.invoke(y, sin(2f * PI.toFloat() * (k + 0.5f) / sides)) == true) continue
                    v(i, k, r); v(i, k + 1, r); v(i + 1, k + 1, r)
                    v(i, k, r); v(i + 1, k + 1, r); v(i + 1, k, r)
                }
            }
            for ((i, cap) in listOf(0 to bottomCap, n - 1 to topCap)) {
                if (!cap) continue
                val r = role(rings[i][0])
                val ny = if (i == 0) -1f else 1f
                val ring = rings[i]
                for (k in 0 until sides) {
                    val a = p(i, k); val b = p(i, k + 1)
                    vertex(cx, ring[0], cz + ring[3], 0f, ny, 0f, r)
                    vertex(a[0], a[1], a[2], 0f, ny, 0f, r)
                    vertex(b[0], b[1], b[2], 0f, ny, 0f, r)
                }
            }
        }

        fun floats() = FloatArray(f.size) { f[it] }
        fun roleArray() = IntArray(roles.size) { roles[it] }
    }

    companion object {
        /** Most cars and people at once, and how far round the player they appear and are dropped. */
        const val MAX_CARS = 18
        const val MAX_WALKERS = 30
        private const val CAR_APPEAR = 75f
        private const val CAR_SPAWN_MAX = 170f
        private const val CAR_FORGET = 230f
        private const val WALKER_APPEAR = 55f
        private const val WALKER_SPAWN_MAX = 110f
        private const val WALKER_FORGET = 150f
        private const val PANIC_RADIUS = 45f
        private const val PANIC_SECONDS = 9f
        /** How long turning round to run away takes (the "Running Turn 180" clip), speeding up meanwhile. */
        const val TURN_SECONDS = 0.67f
        /** Being shot: how near a bullet must pass, how tall people are, how they fall, and how long bodies stay. */
        private const val HIT_RADIUS = 0.3f
        private const val PERSON_HEIGHT = 1.8f
        private const val FALL_SECONDS = 0.7f
        private const val BODY_SECONDS = 30f
        private const val FALL_BACK = 1
        private const val FALL_FORWARD = 2
        private const val THROWN_BACK = 3
        private const val CELL = 64f

        // Colour roles; car roles are their palette entries, people's are looked up per person.
        private const val PAINT = -1
        private const val GLASS = 8
        private const val TYRE = 9
        private const val SIGN = 45
        private const val RED_PLATE = 46
        private const val CHROME = 47
        private const val SPOKE = 48

        // Parts of a body ring (see [Shape.loft]), and what a stretch of cabin is.
        private const val BOTTOM = 0
        private const val SIDE = 1
        private const val TOP = 2
        private const val PILLAR = 0
        private const val WINDSCREEN = 1
        private const val SIDE_WINDOWS = 2
        private const val REAR_WINDOW = 3

        /** The angle (degrees, 0 = +x, 90 = up) of point [i] of a ring with [k] points on each corner. */
        private fun ringAngle(i: Int, k: Int) = (i / k) * 90f - 90f + 90f * (i % k) / (k - 1)

        /** Which part of a ring the stretch from point [j] to the next is: under it, its sides, or its top. */
        private fun ringPart(j: Int, k: Int): Int {
            val a0 = ringAngle(j, k)
            val a1 = if (j + 1 == 4 * k) 270f else ringAngle(j + 1, k)
            return when {
                a0 >= 45f && a1 <= 135f && a1 >= 45f && a0 <= 135f -> TOP
                a0 >= 0f && a1 >= 0f && a0 <= 180f && a1 <= 180f -> SIDE
                else -> BOTTOM
            }
        }
        private const val HEADLIGHT = 11
        private const val TAILLIGHT = 12
        private const val TRIM = 13
        private const val PLATE = 14
        private const val SKIN = 100
        private const val SHIRT = 101
        private const val LEGS = 102
        private const val FEET = 103
        private const val HAIR_ROLE = 104
        private const val OUTER_ROLE = 105
        private const val BAG_ROLE = 106
        /** Eyes (the dark trim colour) and lips: fixed palette entries. */
        private const val DARK = 13
        private const val LIPS = 49

        private const val LEG_UPPER = 1
        private const val LEG_LOWER = 2
        private const val ARM_UPPER = 3
        private const val ARM_LOWER = 4

        /** The colours cars and people are painted in (see [CityTextures.palette]): 8 × 8. */
        val PALETTE = intArrayOf(
            // Car paints.
            0xFFF2F2F0.toInt(), 0xFFE4E2DA.toInt(), 0xFFB9BDC1.toInt(), 0xFF6E7378.toInt(),
            0xFF1C1E21.toInt(), 0xFF9E1B1B.toInt(), 0xFF1F3F7A.toInt(), 0xFFC9B48E.toInt(),
            // Glass, tyre, rim, headlight, tail light, trim, number plate, a dark green paint.
            0xFF26313A.toInt(), 0xFF1C1C1C.toInt(), 0xFFB5B9BD.toInt(), 0xFFFFF4D2.toInt(),
            0xFFB3201B.toInt(), 0xFF262626.toInt(), 0xFFF0F0F0.toInt(), 0xFF234D33.toInt(),
            // Skin.
            0xFFF1C9A5.toInt(), 0xFFD9A47A.toInt(), 0xFFB57B52.toInt(), 0xFF7A4E30.toInt(),
            // Shirts.
            0xFFF4F4F2.toInt(), 0xFF22304F.toInt(), 0xFF1E1E1E.toInt(), 0xFFA3262A.toInt(),
            0xFF5E6B3A.toInt(), 0xFF7FA9CF.toInt(), 0xFF6B2236.toInt(), 0xFFC9A03A.toInt(),
            0xFF8A8D90.toInt(), 0xFF2E7C7A.toInt(), 0xFFD98FA6.toInt(), 0xFFD8C8A8.toInt(),
            // Trousers and skirts.
            0xFF2F4A6E.toInt(), 0xFF202022.toInt(), 0xFFB9A57E.toInt(), 0xFF6F7275.toInt(),
            0xFF1F2738.toInt(), 0xFF5A4232.toInt(),
            // Shoes.
            0xFF151515.toInt(), 0xFFEDEDED.toInt(), 0xFF5A3A24.toInt(),
            // Hair.
            0xFF1A1512.toInt(), 0xFF3B2618.toInt(), 0xFF6A4528.toInt(), 0xFF9A9A96.toInt(),
            // Taxi sign, red (taxi) number plate, chrome, dark rim spokes.
            0xFFF2C230.toInt(), 0xFFC4262A.toInt(), 0xFFD9DDE0.toInt(), 0xFF4A4D50.toInt(),
            // Lips; then jacket, headscarf and dress colours.
            0xFF9C5A50.toInt(), 0xFF3A3F47.toInt(), 0xFF6B5A4A.toInt(), 0xFF2B4A44.toInt(), 0xFF7A2E3A.toInt(), 0xFFB8A48A.toInt(),
            0xFF4C3F66.toInt(), 0xFF8C6B4A.toInt(),
        )
        private val CAR_PAINTS = intArrayOf(0, 0, 0, 1, 2, 2, 3, 4, 4, 5, 6, 7, 15)
        private val SKINS = intArrayOf(16, 17, 17, 18, 19)
        private val SHIRTS = (20..31).toList().toIntArray()
        private val PANTS = (32..37).toList().toIntArray()
        private val SHOES = intArrayOf(38, 38, 39, 40)
        private val HAIR = intArrayOf(41, 41, 42, 43, 44)
        private val OUTER = intArrayOf(21, 22, 26, 31, 50, 51, 52, 53, 54, 55, 56)
        private val BAGS = intArrayOf(22, 37, 34, 21, 56)

        /** Cars with full detail this close to the camera; further off, the lighter model. */
        private const val CAR_DETAIL_RANGE = 60f
        /** The furthest the front wheels steer, radians. */
        private const val MAX_STEER = 0.6f

        /**
         * A car: its [body] and [cabin] as cross-sections from the front (+z) to the back, its
         * wheels, and whether it's a Beirut "service" taxi (roof sign, red plates) or a panel van.
         */
        private class CarSpec(
            val body: List<Section>, val cabin: List<Section>, val wheelR: Float, val axles: FloatArray,
            val taxi: Boolean = false, val van: Boolean = false,
        )

        /** A body cross-section: z, bottom, top, half widths at bottom and top, corner radii below and above. */
        private fun body(vararg v: Float) = Section(v[0], v[1], v[2], v[3], v[4], 0.06f, v[5])

        /**
         * A cabin cross-section on the body at [base], [half] wide there: z, roof height, half
         * width at the roof, roof corner radius, and what the stretch to the next section is.
         */
        private fun cabin(base: Float, half: Float, z: Float, top: Float, topHalf: Float, r: Float, span: Int) =
            Section(z, base, top, half, topHalf, 0.03f, r, span)

        private fun car(spec: CarSpec, detail: Boolean): Model {
            val s = Shape()
            val k = if (detail) 3 else 2
            val bottom = spec.body.minOf { it.yb }
            s.loft(spec.body, k, PAINT) { part, _, y -> if (part == BOTTOM || y < bottom + 0.12f) TRIM else PAINT }
            s.loft(spec.cabin, k, PAINT) { part, from, _ ->
                when (from.span) {
                    WINDSCREEN, REAR_WINDOW -> if (part == TOP) GLASS else PAINT
                    SIDE_WINDOWS -> if (part == SIDE) GLASS else PAINT
                    else -> PAINT
                }
            }
            val front = spec.body.first(); val back = spec.body.last()
            val half = spec.body.maxOf { it.hwb }
            // Front: headlights, grille, air intake, number plate.
            val fz = front.z + 0.006f
            val bz = back.z - 0.006f
            val plate = if (spec.taxi) RED_PLATE else PLATE
            for (side in floatArrayOf(1f, -1f)) {
                s.panelZ(fz, side * front.hwt * 0.5f, side * front.hwt * 0.92f, front.yt - 0.14f, front.yt - 0.04f, HEADLIGHT)
                s.panelZ(bz, side * back.hwt * 0.5f, side * back.hwt * 0.92f, back.yt - 0.15f, back.yt - 0.04f, TAILLIGHT)
            }
            s.panelZ(fz, -front.hwt * 0.42f, front.hwt * 0.42f, front.yt - 0.2f, front.yt - 0.05f, TRIM)
            s.panelZ(fz, -front.hwb * 0.78f, front.hwb * 0.78f, front.yb + 0.03f, front.yb + 0.12f, TRIM)
            s.panelZ(fz + 0.003f, -0.26f, 0.26f, front.yb + 0.13f, front.yb + 0.24f, plate)
            s.panelZ(bz, -back.hwb * 0.9f, back.hwb * 0.9f, back.yb + 0.02f, back.yb + 0.1f, TRIM)
            s.panelZ(bz - 0.003f, -0.26f, 0.26f, back.yb + 0.2f, back.yb + 0.31f, plate)
            if (spec.van) s.panelZ(bz, -half * 0.78f, half * 0.78f, spec.cabin.last().yt - 0.6f, spec.cabin.last().yt - 0.12f, GLASS)
            // Wheels in dark arches.
            for (z in spec.axles) for (side in floatArrayOf(1f, -1f)) {
                s.arch(side * (half + 0.004f), spec.wheelR, z, spec.wheelR + 0.07f)
                s.wheel(side * (half - 0.09f), spec.wheelR, z, spec.wheelR, 0.21f, if (detail) 10 else 6, spokes = detail)
            }
            if (detail) {
                val glassFront = spec.cabin.first()
                val beltY = glassFront.yb
                // Door seams and handles; side mirrors at the foot of the windscreen.
                val seams = listOf(glassFront.z - 0.05f, (glassFront.z + spec.cabin.last().z) / 2f - 0.1f, spec.cabin.last().z + 0.3f)
                for (side in floatArrayOf(1f, -1f)) {
                    val x = side * (half + 0.003f)
                    val doors = if (spec.van) seams.take(1) else seams
                    for (z in doors) s.panelX(x, z - 0.008f, z + 0.008f, bottom + 0.12f, beltY - 0.04f, TRIM)
                    for (z in doors.dropLast(1).ifEmpty { doors }) s.panelX(x * 1.001f, z - 0.32f, z - 0.18f, beltY - 0.13f, beltY - 0.09f, CHROME)
                    val mx = side * glassFront.hwb
                    s.box(mx, beltY + 0.03f, glassFront.z - 0.2f, mx + side * 0.17f, beltY + 0.14f, glassFront.z - 0.08f, PAINT)
                }
            }
            if (spec.taxi) {
                val roof = spec.cabin.maxOf { it.yt }
                s.box(-0.22f, roof - 0.02f, -0.45f, 0.22f, roof + 0.13f, -0.15f, SIGN)
            }
            return Model(s.floats(), s.roleArray(), (front.z - back.z) / 2f, half)
        }

        private val CAR_SPECS = listOf(
            // Saloon: long bonnet and boot (the old Mercedes of Beirut's streets).
            CarSpec(
                listOf(
                    body(2.35f, 0.30f, 0.70f, 0.80f, 0.74f, 0.14f), body(2.25f, 0.28f, 0.76f, 0.87f, 0.80f, 0.16f),
                    body(1.90f, 0.27f, 0.80f, 0.89f, 0.83f, 0.16f), body(1.05f, 0.27f, 0.88f, 0.90f, 0.84f, 0.18f),
                    body(-0.30f, 0.27f, 0.92f, 0.90f, 0.84f, 0.18f), body(-1.65f, 0.28f, 0.94f, 0.89f, 0.83f, 0.18f),
                    body(-2.15f, 0.29f, 0.92f, 0.86f, 0.80f, 0.16f), body(-2.35f, 0.32f, 0.86f, 0.80f, 0.74f, 0.14f),
                ),
                listOf(
                    cabin(0.84f, 0.82f, 1.10f, 0.90f, 0.80f, 0.03f, WINDSCREEN), cabin(0.84f, 0.82f, 0.25f, 1.42f, 0.68f, 0.12f, SIDE_WINDOWS),
                    cabin(0.84f, 0.82f, -0.30f, 1.43f, 0.68f, 0.12f, PILLAR), cabin(0.84f, 0.82f, -0.40f, 1.43f, 0.68f, 0.12f, SIDE_WINDOWS),
                    cabin(0.84f, 0.82f, -1.00f, 1.41f, 0.68f, 0.12f, REAR_WINDOW), cabin(0.84f, 0.82f, -1.70f, 0.96f, 0.80f, 0.03f, PILLAR),
                ),
                wheelR = 0.32f, axles = floatArrayOf(1.40f, -1.42f),
            ),
            // Hatchback.
            CarSpec(
                listOf(
                    body(1.98f, 0.30f, 0.68f, 0.78f, 0.72f, 0.14f), body(1.88f, 0.28f, 0.74f, 0.84f, 0.78f, 0.16f),
                    body(1.55f, 0.27f, 0.80f, 0.86f, 0.80f, 0.16f), body(0.85f, 0.27f, 0.88f, 0.87f, 0.81f, 0.18f),
                    body(-1.55f, 0.28f, 0.92f, 0.86f, 0.80f, 0.18f), body(-1.90f, 0.30f, 0.90f, 0.83f, 0.77f, 0.16f),
                    body(-2.00f, 0.34f, 0.86f, 0.78f, 0.72f, 0.14f),
                ),
                listOf(
                    cabin(0.84f, 0.79f, 0.90f, 0.90f, 0.77f, 0.03f, WINDSCREEN), cabin(0.84f, 0.79f, 0.15f, 1.44f, 0.66f, 0.12f, SIDE_WINDOWS),
                    cabin(0.84f, 0.79f, -0.35f, 1.45f, 0.66f, 0.12f, PILLAR), cabin(0.84f, 0.79f, -0.45f, 1.45f, 0.66f, 0.12f, SIDE_WINDOWS),
                    cabin(0.84f, 0.79f, -1.55f, 1.42f, 0.66f, 0.12f, REAR_WINDOW), cabin(0.84f, 0.79f, -1.85f, 0.96f, 0.74f, 0.05f, PILLAR),
                ),
                wheelR = 0.30f, axles = floatArrayOf(1.25f, -1.28f),
            ),
            // SUV: high, square and wide.
            CarSpec(
                listOf(
                    body(2.30f, 0.40f, 0.84f, 0.86f, 0.80f, 0.16f), body(2.18f, 0.38f, 0.92f, 0.93f, 0.87f, 0.18f),
                    body(1.70f, 0.37f, 1.00f, 0.95f, 0.89f, 0.18f), body(0.95f, 0.37f, 1.06f, 0.96f, 0.90f, 0.20f),
                    body(-2.10f, 0.38f, 1.10f, 0.95f, 0.89f, 0.20f), body(-2.30f, 0.42f, 1.06f, 0.90f, 0.84f, 0.18f),
                ),
                listOf(
                    cabin(1.0f, 0.9f, 1.00f, 1.07f, 0.88f, 0.03f, WINDSCREEN), cabin(1.0f, 0.9f, 0.30f, 1.74f, 0.80f, 0.12f, SIDE_WINDOWS),
                    cabin(1.0f, 0.9f, -0.40f, 1.76f, 0.80f, 0.12f, PILLAR), cabin(1.0f, 0.9f, -0.50f, 1.76f, 0.80f, 0.12f, SIDE_WINDOWS),
                    cabin(1.0f, 0.9f, -1.95f, 1.76f, 0.80f, 0.12f, REAR_WINDOW), cabin(1.0f, 0.9f, -2.20f, 1.12f, 0.86f, 0.05f, PILLAR),
                ),
                wheelR = 0.38f, axles = floatArrayOf(1.42f, -1.42f),
            ),
            // Panel van: windows only in the cab.
            CarSpec(
                listOf(
                    body(2.42f, 0.36f, 0.80f, 0.88f, 0.82f, 0.14f), body(2.30f, 0.34f, 0.90f, 0.95f, 0.90f, 0.16f),
                    body(1.80f, 0.34f, 1.00f, 0.97f, 0.92f, 0.18f), body(-2.40f, 0.36f, 1.02f, 0.97f, 0.92f, 0.18f),
                    body(-2.45f, 0.40f, 1.00f, 0.94f, 0.90f, 0.16f),
                ),
                listOf(
                    cabin(0.96f, 0.95f, 1.80f, 1.02f, 0.92f, 0.03f, WINDSCREEN), cabin(0.96f, 0.95f, 1.05f, 1.92f, 0.88f, 0.14f, SIDE_WINDOWS),
                    cabin(0.96f, 0.95f, 0.35f, 1.98f, 0.90f, 0.14f, PILLAR), cabin(0.96f, 0.95f, -2.42f, 1.98f, 0.90f, 0.14f, PILLAR),
                ),
                wheelR = 0.34f, axles = floatArrayOf(1.55f, -1.60f), van = true,
            ),
        )

        private val CAR_MODELS = CAR_SPECS.map { car(it, detail = true) } + car(CarSpec(CAR_SPECS[0].body, CAR_SPECS[0].cabin, 0.32f, CAR_SPECS[0].axles, taxi = true), true)
        private val CAR_MODELS_FAR = CAR_SPECS.map { car(it, detail = false) } + car(CarSpec(CAR_SPECS[0].body, CAR_SPECS[0].cabin, 0.32f, CAR_SPECS[0].axles, taxi = true), false)
        /** How often each model is picked: saloons and taxis most, a van now and then. */
        private val CAR_MIX = intArrayOf(0, 0, 0, 1, 1, 2, 2, 3, 4, 4)

        /** People closer than this get the detailed model (face, hands, rounder limbs). */
        private const val PERSON_DETAIL_RANGE = 30f
        /** Height of the hips, which a running body leans forward from. */
        private const val HIP_Y = 0.95f

        // What people wear.
        private const val TEE = 0
        private const val LONG_SLEEVES = 1
        private const val JACKET = 2
        private const val TROUSERS = 0
        private const val SKIRT = 1
        private const val DRESS = 2
        private const val SHORT_HAIR = 0
        private const val LONG_HAIR = 1
        private const val HIJAB = 2
        private const val BALD = 3
        private const val BOB = 4
        private const val NO_BAG = 0
        private const val BACKPACK = 1
        private const val HANDBAG = 2

        /** A kind of person: build, clothes, hair, and what they carry; [height] scales them. */
        private class PersonSpec(
            val woman: Boolean, val top: Int, val bottom: Int, val hair: Int,
            val beard: Boolean = false, val bag: Int = NO_BAG, val height: Float = 1f,
        )

        private val PEOPLE = listOf(
            PersonSpec(false, TEE, TROUSERS, SHORT_HAIR),
            PersonSpec(false, LONG_SLEEVES, TROUSERS, SHORT_HAIR, beard = true),
            PersonSpec(false, JACKET, TROUSERS, SHORT_HAIR),
            PersonSpec(false, TEE, TROUSERS, BALD, beard = true, bag = BACKPACK),
            PersonSpec(false, LONG_SLEEVES, TROUSERS, SHORT_HAIR, bag = BACKPACK),
            PersonSpec(true, TEE, TROUSERS, LONG_HAIR, bag = HANDBAG, height = 0.95f),
            PersonSpec(true, LONG_SLEEVES, SKIRT, LONG_HAIR, height = 0.95f),
            PersonSpec(true, LONG_SLEEVES, DRESS, HIJAB, bag = HANDBAG, height = 0.94f),
            PersonSpec(true, JACKET, TROUSERS, HIJAB, height = 0.95f),
            PersonSpec(true, TEE, DRESS, BOB, height = 0.94f),
        )

        private fun ring(y: Float, rx: Float, rz: Float, dz: Float = 0f) = floatArrayOf(y, rx, rz, dz)

        /**
         * A person of kind [p] in model space, facing +z, about 1.75 m tall before scaling. The
         * far model has fewer sides and rings, and no face or fingers to speak of.
         */
        private fun buildPerson(p: PersonSpec, detail: Boolean): List<Part> = buildList {
            val sides = if (detail) 8 else 5
            // Far away, only a column's ends (and middle, on the body and head) are kept.
            fun lod(rings: List<FloatArray>) =
                if (detail || rings.size <= 2) rings
                else rings.filterIndexed { i, _ -> i == 0 || i == rings.size - 1 || (rings.size >= 5 && i == rings.size / 2) }
            fun part(limb: Int = 0, side: Float = 1f, pivot: Float = 0f, lowerPivot: Float = 0f, upper: Boolean = false, build: Shape.() -> Unit) {
                val s = Shape().apply(build)
                add(Part(s.floats(), s.roleArray(), limb, side, pivot, lowerPivot, upper))
            }
            val w = p.woman
            val dress = p.bottom == DRESS
            val legRole = if (p.bottom == TROUSERS) LEGS else SKIN
            val topRole = if (dress || p.top == JACKET) OUTER_ROLE else SHIRT
            val longSleeves = p.top != TEE
            val shoulder = if (w) 0.17f else 0.2f
            val jacket = if (p.top == JACKET) 0.012f else 0f

            for (side in floatArrayOf(1f, -1f)) {
                val x = (if (w) 0.098f else 0.095f) * side
                part(LEG_UPPER, side, pivot = HIP_Y) {
                    column(x, 0f, lod(listOf(ring(0.5f, 0.056f, 0.062f), ring(0.74f, 0.072f, 0.08f, 0.008f), ring(0.97f, 0.086f, 0.093f))), sides, { legRole })
                }
                part(LEG_LOWER, side, pivot = HIP_Y, lowerPivot = 0.5f) {
                    val hem = if (p.bottom == TROUSERS) 0.046f else 0.034f
                    column(x, 0f, lod(listOf(ring(0.085f, hem, hem + 0.004f), ring(0.17f, 0.04f, 0.044f), ring(0.37f, 0.056f, 0.062f, -0.01f), ring(0.53f, 0.057f, 0.063f))), sides, { legRole })
                    // The shoe, pointing forwards.
                    column(x, 0f, lod(listOf(ring(0f, 0.047f, 0.125f, 0.045f), ring(0.045f, 0.05f, 0.125f, 0.045f), ring(0.1f, 0.037f, 0.055f, 0.008f))), sides, { FEET }, bottomCap = true, topCap = true)
                }
                // Arms, on the opposite phase to the leg on their side.
                // Clear of a skirt's flare at the hands.
                val ax = (shoulder + 0.016f + jacket + if (p.bottom == TROUSERS) 0f else 0.015f) * side
                val armRadius = if (w) 0.9f else 1f
                part(ARM_UPPER, -side, pivot = 1.45f, upper = true) {
                    column(ax, 0f, lod(listOf(ring(1.17f, 0.037f * armRadius + jacket, 0.041f + jacket), ring(1.32f, 0.044f * armRadius + jacket, 0.048f + jacket), ring(1.43f, 0.049f + jacket, 0.053f + jacket), ring(1.475f, 0.036f + jacket, 0.04f + jacket))), sides,
                        { y -> if (longSleeves || y > 1.32f) topRole else SKIN })
                }
                part(ARM_LOWER, -side, pivot = 1.45f, lowerPivot = 1.17f, upper = true) {
                    column(ax, 0f, lod(listOf(ring(0.93f, 0.027f, 0.03f), ring(1.06f, 0.034f * armRadius, 0.037f), ring(1.18f, 0.037f * armRadius + jacket, 0.041f + jacket))), sides,
                        { if (longSleeves) topRole else SKIN })
                    // The hand, flat, palm in.
                    column(ax, 0.004f, lod(listOf(ring(0.83f, 0.016f, 0.026f), ring(0.885f, 0.021f, 0.035f), ring(0.935f, 0.025f, 0.029f))), if (detail) 6 else 4, { SKIN }, bottomCap = true)
                }
            }

            // Hips, and a skirt or dress hanging from them.
            val hips = if (w) 0.18f else 0.165f
            part {
                column(0f, 0f, lod(listOf(ring(0.86f, hips - 0.015f, 0.1f), ring(0.95f, hips, 0.108f), ring(1.02f, hips - 0.03f, 0.094f))), sides + 2,
                    { if (p.bottom == TROUSERS) LEGS else if (dress) OUTER_ROLE else LEGS }, bottomCap = true)
            }
            if (p.bottom != TROUSERS) part {
                column(0f, 0f, lod(listOf(ring(0.5f, 0.235f, 0.19f), ring(0.75f, 0.215f, 0.165f, 0.005f), ring(0.92f, 0.198f, 0.13f), ring(1.0f, 0.175f, 0.118f))), sides + 2,
                    { if (dress) OUTER_ROLE else LEGS }, bottomCap = true)
            }

            // Body: waist, chest (a bust on women), shoulders.
            val body = if (w) listOf(
                ring(0.97f, 0.16f, 0.105f), ring(1.03f, 0.14f, 0.097f), ring(1.11f, 0.135f, 0.095f), ring(1.24f, 0.15f, 0.115f, 0.015f), ring(1.33f, 0.16f, 0.128f, 0.024f),
                ring(1.42f, 0.17f, 0.105f, 0.006f), ring(1.48f, 0.13f, 0.08f), ring(1.51f, 0.065f, 0.055f),
            ) else listOf(
                ring(0.97f, 0.165f, 0.108f), ring(1.03f, 0.152f, 0.101f), ring(1.12f, 0.155f, 0.1f, 0.005f), ring(1.26f, 0.17f, 0.11f, 0.01f), ring(1.36f, 0.185f, 0.12f, 0.015f),
                ring(1.44f, 0.2f, 0.11f, 0.005f), ring(1.5f, 0.15f, 0.085f), ring(1.53f, 0.07f, 0.06f),
            )
            part(upper = true) {
                column(0f, 0f, lod(body.map { r -> floatArrayOf(r[0], r[1] + jacket, r[2] + jacket, r[3]) }), sides + 2, { topRole }, topCap = true)
                if (p.top == JACKET) {
                    // The shirt showing in the jacket's open front.
                    val z = (if (w) 0.105f else 0.118f) + jacket + 0.004f
                    tri(floatArrayOf(-0.04f, 1.47f, z), floatArrayOf(0.04f, 1.47f, z), floatArrayOf(0f, 1.3f, z + 0.01f), SHIRT, floatArrayOf(0f, 1.35f, 0f))
                }
            }

            // Neck and head.
            val headScale = if (w) 0.95f else 1f
            val head = listOf(
                ring(1.575f, 0.045f, 0.05f, 0.025f), ring(1.6f, 0.065f, 0.075f, 0.015f), ring(1.64f, 0.08f, 0.092f, 0.008f),
                ring(1.69f, 0.088f, 0.1f, 0.005f), ring(1.74f, 0.088f, 0.102f), ring(1.785f, 0.074f, 0.09f, -0.005f),
                ring(1.815f, 0.045f, 0.058f, -0.008f), ring(1.83f, 0.012f, 0.015f, -0.01f),
            ).map { r -> floatArrayOf(r[0], r[1] * headScale, r[2] * headScale, r[3]) }
            part(upper = true) {
                column(0f, 0f, lod(listOf(ring(1.48f, 0.048f, 0.05f), ring(1.6f, 0.045f, 0.048f))), sides, { SKIN })
                column(0f, 0f, lod(head), sides + 1, { SKIN }, topCap = true)
                if (detail) {
                    // Nose, ears, eyes, eyebrows and mouth.
                    blob(0f, 1.675f, 0.1f * headScale, 0.014f, 0.024f, 0.02f, SKIN, stacks = 2, slices = 5)
                    if (p.hair != HIJAB) for (s in floatArrayOf(1f, -1f)) blob(s * 0.087f * headScale, 1.69f, -0.005f, 0.012f, 0.028f, 0.02f, SKIN, stacks = 2, slices = 5)
                    val face = 0.097f * headScale
                    val inner = floatArrayOf(0f, 1.69f, 0f)
                    for (s in floatArrayOf(1f, -1f)) {
                        quad(floatArrayOf(s * 0.022f, 1.703f, face), floatArrayOf(s * 0.042f, 1.703f, face), floatArrayOf(s * 0.042f, 1.714f, face), floatArrayOf(s * 0.022f, 1.714f, face), DARK, inner)
                        quad(floatArrayOf(s * 0.018f, 1.726f, face - 0.002f), floatArrayOf(s * 0.047f, 1.726f, face - 0.002f), floatArrayOf(s * 0.047f, 1.733f, face - 0.003f), floatArrayOf(s * 0.018f, 1.733f, face - 0.003f), HAIR_ROLE, inner)
                    }
                    quad(floatArrayOf(-0.018f, 1.634f, face + 0.003f), floatArrayOf(0.018f, 1.634f, face + 0.003f), floatArrayOf(0.018f, 1.641f, face + 0.003f), floatArrayOf(-0.018f, 1.641f, face + 0.003f), LIPS, inner)
                }
            }

            // Hair, a headscarf, a beard.
            val front = 0.45f
            fun cap(rings: List<FloatArray>, hairline: Float, role: Int) = part(upper = true) {
                column(0f, 0f, lod(rings.map { r -> floatArrayOf(r[0], r[1] * headScale, r[2] * headScale, r[3]) }), sides + 1, { role }, topCap = true) { y, forward ->
                    y < hairline && forward > front
                }
            }
            when (p.hair) {
                SHORT_HAIR -> cap(listOf(
                    ring(1.655f, 0.096f, 0.104f, -0.016f), ring(1.72f, 0.099f, 0.112f, -0.005f), ring(1.775f, 0.089f, 0.105f, -0.006f),
                    ring(1.818f, 0.064f, 0.08f, -0.009f), ring(1.846f, 0.022f, 0.028f, -0.01f),
                ), 1.765f, HAIR_ROLE)
                BOB, LONG_HAIR -> {
                    cap(listOf(
                        ring(1.6f, 0.1f, 0.108f, -0.022f), ring(1.66f, 0.101f, 0.112f, -0.012f), ring(1.72f, 0.099f, 0.113f, -0.004f),
                        ring(1.775f, 0.091f, 0.106f, -0.006f), ring(1.818f, 0.066f, 0.082f, -0.009f), ring(1.848f, 0.022f, 0.028f, -0.01f),
                    ), 1.765f, HAIR_ROLE)
                    if (p.hair == LONG_HAIR) part(upper = true) {
                        column(0f, -0.06f, lod(listOf(ring(1.36f, 0.095f, 0.04f), ring(1.5f, 0.105f, 0.055f), ring(1.64f, 0.1f, 0.06f), ring(1.73f, 0.088f, 0.05f))), sides, { HAIR_ROLE }, bottomCap = true)
                    }
                }
                HIJAB -> {
                    cap(listOf(
                        ring(1.56f, 0.075f, 0.085f), ring(1.6f, 0.1f, 0.11f), ring(1.66f, 0.102f, 0.113f, -0.005f), ring(1.72f, 0.102f, 0.116f, -0.004f),
                        ring(1.78f, 0.094f, 0.11f, -0.006f), ring(1.822f, 0.068f, 0.084f, -0.009f), ring(1.852f, 0.022f, 0.028f, -0.01f),
                    ), 1.775f, OUTER_ROLE)
                    // Falling over the neck and shoulders.
                    part(upper = true) { column(0f, 0f, lod(listOf(ring(1.42f, 0.19f, 0.125f), ring(1.5f, 0.155f, 0.115f), ring(1.585f, 0.085f, 0.095f))), sides + 2, { OUTER_ROLE }) }
                }
            }
            if (p.beard) part(upper = true) {
                column(0f, 0f, lod(listOf(ring(1.57f, 0.049f, 0.057f, 0.03f), ring(1.6f, 0.07f, 0.081f, 0.019f), ring(1.645f, 0.085f, 0.097f, 0.01f), ring(1.67f, 0.09f, 0.101f, 0.007f))), sides + 1, { HAIR_ROLE }) { _, forward ->
                    forward < -0.2f
                }
            }

            // What they carry.
            when (p.bag) {
                BACKPACK -> part(upper = true) {
                    box(-0.14f, 1.08f, -0.23f, 0.14f, 1.42f, -0.1f, BAG_ROLE)
                    for (s in floatArrayOf(1f, -1f)) box(s * 0.075f - 0.016f, 1.24f, 0.112f, s * 0.075f + 0.016f, 1.46f, 0.124f, BAG_ROLE)
                }
                HANDBAG -> part(upper = true) {
                    val bx = shoulder + 0.085f
                    box(bx, 0.9f, -0.07f, bx + 0.06f, 1.08f, 0.08f, BAG_ROLE)
                    box(bx + 0.02f, 1.08f, -0.012f, bx + 0.035f, 1.47f, 0.004f, BAG_ROLE)
                }
            }
        }

        private val PEOPLE_NEAR = PEOPLE.map { buildPerson(it, detail = true) }
        private val PEOPLE_FAR = PEOPLE.map { buildPerson(it, detail = false) }
    }
}
