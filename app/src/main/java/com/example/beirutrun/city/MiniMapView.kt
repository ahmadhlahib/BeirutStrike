package com.example.beirutrun.city

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.beirutrun.PhotoDrop
import com.example.beirutrun.online.RemotePlayer
import kotlin.concurrent.thread

/**
 * A north-up map: the real streets (drawn once, off the main thread), the play area's border (red),
 * [players] (team colours; CityActivity passes only teammates), dropped photos (yellow) and the player as an arrow. Redraws ten
 * times a second from [renderer]'s position.
 *
 * As the corner minimap it follows the player; with [full] set it shows the whole play area (or the
 * whole map when there is no limit), fitted to the view.
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var city: CityMap? = null
        set(value) {
            field = value
            if (value != null && !full) renderMap(value, RectF(value.minX, value.minZ, value.maxX, value.maxZ), 1f)
            if (value != null && full && width > 0) renderFull(value)
        }
    /** Show the whole play area instead of following the player. Set before [city]. */
    var full = false
    var renderer: CityRenderer? = null
    var drops: List<PhotoDrop> = emptyList()
    var players: List<RemotePlayer> = emptyList()
    /** Where enemies roughly are (see [EnemyAreas]): pulsing red circles, never their exact spot. */
    var enemyAreas: List<EnemyAreas.Area> = emptyList()
    /** Dot colour for a team id (null = default blue). */
    var teamColor: (String) -> Int? = { null }

    @Volatile private var mapBitmap: Bitmap? = null
    /** The part of the map [mapBitmap] shows, and its pixels per metre. */
    @Volatile private var bitmapArea = RectF()
    @Volatile private var bitmapScale = 1f

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val arrow = Path()

    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 100)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 14f * density, 14f * density, Path.Direction.CW)
        val s = 7f * density
        arrow.reset()
        arrow.moveTo(0f, -s * 1.3f)
        arrow.lineTo(s, s)
        arrow.lineTo(0f, s * 0.4f)
        arrow.lineTo(-s, s)
        arrow.close()
        if (full && w > 0) city?.let { renderFull(it) }
    }

    /** What the full map shows: the play area with a little of the city round it. */
    private fun region(map: CityMap): RectF {
        if (!map.limited) return RectF(map.minX, map.minZ, map.maxX, map.maxZ)
        val pad = maxOf(map.playMaxX - map.playMinX, map.playMaxZ - map.playMinZ) * 0.08f
        return RectF(map.playMinX - pad, map.playMinZ - pad, map.playMaxX + pad, map.playMaxZ + pad)
    }

    /** The full map: [region] fitted to the view, drawn at screen resolution so it stays sharp. */
    private fun renderFull(map: CityMap) {
        val area = region(map)
        renderMap(map, area, minOf(width / area.width(), height / area.height()))
    }

    /** Draws [area] of the map at [pxPerMetre], once, off the main thread. */
    private fun renderMap(map: CityMap, area: RectF, pxPerMetre: Float) = thread(name = "minimap") {
        val w = (area.width() * pxPerMetre).toInt().coerceAtLeast(1)
        val h = (area.height() * pxPerMetre).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bitmap)
        c.scale(pxPerMetre, pxPerMetre)
        c.translate(-area.left, -area.top)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        c.drawColor(LAND)

        fun polygon(ring: FloatArray, color: Int) {
            val path = Path()
            path.moveTo(ring[0], ring[1])
            for (i in 2 until ring.size step 2) path.lineTo(ring[i], ring[i + 1])
            path.close()
            p.style = Paint.Style.FILL
            p.color = color
            c.drawPath(path, p)
        }

        for (s in map.sea) polygon(s, SEA)
        for (a in map.areas) polygon(a.pts, when (a.kind) {
            CityMap.AREA_PARK, CityMap.AREA_PITCH -> 0xFFBFDDA6.toInt()
            CityMap.AREA_WATER -> SEA
            CityMap.AREA_PARKING -> 0xFFDAD6CE.toInt()
            CityMap.AREA_PLAZA -> 0xFFF4EFE6.toInt()
            else -> 0xFFE2D8C6.toInt()
        })

        // Roads as on a printed street map: every road's darker edge first, then the fills on
        // top, so junctions merge cleanly; quieter roads under busier ones. Footpaths are dashed.
        val roadPaths = map.roads.map { road ->
            Path().apply {
                moveTo(road.pts[0], road.pts[1])
                for (i in 2 until road.pts.size step 2) lineTo(road.pts[i], road.pts[i + 1])
            }
        }
        val order = intArrayOf(CityMap.ROAD_PIER, CityMap.ROAD_PATH, CityMap.ROAD_PEDESTRIAN, CityMap.ROAD_MINOR, CityMap.ROAD_MEDIUM, CityMap.ROAD_MAJOR)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        for (casing in booleanArrayOf(true, false)) for (kind in order) {
            if (kind == CityMap.ROAD_PATH && casing) continue
            p.color = when {
                kind == CityMap.ROAD_PATH -> 0xFFB9A88A.toInt()
                casing && kind == CityMap.ROAD_MAJOR -> 0xFFD9A956.toInt()
                casing && kind == CityMap.ROAD_PEDESTRIAN -> 0xFFD9D0C0.toInt()
                casing -> 0xFFC2BAAB.toInt()
                kind == CityMap.ROAD_MAJOR -> 0xFFFCD27E.toInt()
                kind == CityMap.ROAD_PEDESTRIAN -> 0xFFF8F4EC.toInt()
                kind == CityMap.ROAD_PIER -> 0xFFD6D3CD.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            p.pathEffect = if (kind == CityMap.ROAD_PATH) DashPathEffect(floatArrayOf(2.5f, 2f), 0f) else null
            map.roads.forEachIndexed { i, road ->
                if (road.kind != kind) return@forEachIndexed
                p.strokeWidth = if (kind == CityMap.ROAD_PATH) 1.2f else road.width + if (casing) 1.6f else 0f
                c.drawPath(roadPaths[i], p)
            }
        }
        p.pathEffect = null

        // Buildings: a soft shadow, a fill darker the taller they are, and a fine outline.
        for (b in map.buildings) {
            val path = Path()
            path.moveTo(b.pts[0], b.pts[1])
            for (i in 2 until b.pts.size step 2) path.lineTo(b.pts[i], b.pts[i + 1])
            path.close()
            p.style = Paint.Style.FILL
            p.color = 0x2A000000
            c.save()
            c.translate(0.9f, 0.9f)
            c.drawPath(path, p)
            c.restore()
            p.color = when (b.kind) {
                CityMap.BUILDING_MOSQUE -> 0xFF6E9BCF.toInt()
                CityMap.BUILDING_CHURCH -> 0xFFCB8B6E.toInt()
                else -> blend(0xFFDDD6CB.toInt(), 0xFFB5AA9A.toInt(), (b.height / 40f).coerceIn(0f, 1f))
            }
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 0.5f
            p.color = 0xFFA99E8C.toInt()
            c.drawPath(path, p)
        }
        bitmapArea = area
        bitmapScale = pxPerMetre
        mapBitmap = bitmap
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val map = city ?: return
        val r = renderer ?: return
        canvas.save()
        if (!full) canvas.clipPath(clip)
        canvas.drawColor(LAND)

        val px = r.playerX
        val pz = r.playerZ
        // Screen position of a map point: centred on the player, or the whole region fitted.
        val scale: Float
        val originX: Float
        val originZ: Float
        if (full) {
            val area = region(map)
            scale = minOf(width / area.width(), height / area.height())
            originX = area.left - (width / scale - area.width()) / 2f
            originZ = area.top - (height / scale - area.height()) / 2f
        } else {
            scale = width / VIEW_UNITS
            originX = px - width / 2f / scale
            originZ = pz - height / 2f / scale
        }
        fun sx(x: Float) = (x - originX) * scale
        fun sz(z: Float) = (z - originZ) * scale

        mapBitmap?.let { bitmap ->
            val a = bitmapArea
            rect.set(sx(a.left), sz(a.top), sx(a.left + bitmap.width / bitmapScale), sz(a.top + bitmap.height / bitmapScale))
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        }

        if (map.limited) {
            // The play area's edge; outside it the map is dimmed.
            paint.color = 0x55000000
            val l = sx(map.playMinX); val t = sz(map.playMinZ); val rr = sx(map.playMaxX); val b = sz(map.playMaxZ)
            canvas.drawRect(0f, 0f, width.toFloat(), t, paint)
            canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, t, l, b, paint)
            canvas.drawRect(rr, t, width.toFloat(), b, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = 0xFFFF3B30.toInt()
            canvas.drawRect(l, t, rr, b, paint)
            paint.style = Paint.Style.FILL
        }

        val dot = if (full) 1.6f else 1f
        // Ladders up to the roofs: small yellow squares at their foot.
        for (l in r.ladders) {
            val s = 2.8f * density * dot
            paint.color = 0xFF222222.toInt()
            canvas.drawRect(sx(l.footX) - s - density, sz(l.footZ) - s - density, sx(l.footX) + s + density, sz(l.footZ) + s + density, paint)
            paint.color = 0xFFE5A823.toInt()
            canvas.drawRect(sx(l.footX) - s, sz(l.footZ) - s, sx(l.footX) + s, sz(l.footZ) + s, paint)
        }
        // Arms stores: a green "$" badge.
        for (s in r.stores) {
            val cx = sx(s.x); val cz = sz(s.z); val rad = 6.5f * density * dot
            paint.color = 0xFF1B1B1B.toInt()
            canvas.drawCircle(cx, cz, rad + density, paint)
            paint.color = STORE_GREEN
            canvas.drawCircle(cx, cz, rad, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = rad * 1.5f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText("$", cx, cz + rad * 0.52f, paint)
            paint.isFakeBoldText = false
        }

        // Enemy areas, pulsing like an alarm: a soft red fill and a brighter ring.
        val pulse = 0.5f + 0.5f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() / 1000f * PULSE_SPEED)
        for (a in enemyAreas) {
            val cx = sx(a.x); val cz = sz(a.z); val rad = a.radius * scale
            paint.color = ((0x30 + (0x30 * pulse).toInt()) shl 24) or ENEMY_RED
            canvas.drawCircle(cx, cz, rad, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * density
            paint.color = ((0x90 + (0x6F * pulse).toInt()) shl 24) or ENEMY_RED
            canvas.drawCircle(cx, cz, rad * (0.92f + 0.08f * pulse), paint)
            paint.style = Paint.Style.FILL
        }

        for (p in players) {
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(sx(p.x), sz(p.z), 5f * density * dot, paint)
            paint.color = teamColor(p.team) ?: 0xFF1E88E5.toInt()
            canvas.drawCircle(sx(p.x), sz(p.z), 3.6f * density * dot, paint)
        }

        for (d in drops) {
            paint.color = 0xFF222222.toInt()
            canvas.drawCircle(sx(d.x), sz(d.z), 4.5f * density * dot, paint)
            paint.color = 0xFFFFC107.toInt()
            canvas.drawCircle(sx(d.x), sz(d.z), 3.2f * density * dot, paint)
        }

        canvas.save()
        canvas.translate(sx(px), sz(pz))
        canvas.rotate(Math.toDegrees(r.heading.toDouble()).toFloat())
        canvas.scale(dot, dot)
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        canvas.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE53935.toInt()
        canvas.drawPath(arrow, paint)
        canvas.restore()
        canvas.restore()

        if (full) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xCCFFFFFF.toInt()
        rect.set(1f * density, 1f * density, width - 1f * density, height - 1f * density)
        canvas.drawRoundRect(rect, 13f * density, 13f * density, paint)
        paint.style = Paint.Style.FILL
    }

    companion object {
        /** Metres shown across the corner minimap. */
        private const val VIEW_UNITS = 160f
        private const val LAND = 0xFFECE7DE.toInt()
        private const val SEA = 0xFF8EC1E3.toInt()
        /** Enemy areas: red (alpha added as it pulses), and pulses per second × 2π. */
        private const val ENEMY_RED = 0xFF1744
        private const val STORE_GREEN = 0xFF2E7D32.toInt()
        private const val PULSE_SPEED = 5f

        /** [a] blended towards [b] by [t] (0..1), channel by channel. */
        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() and 0xFF) shl shift
            return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
        }
    }
}
