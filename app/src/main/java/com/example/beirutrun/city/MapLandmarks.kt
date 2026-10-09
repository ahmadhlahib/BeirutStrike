package com.example.beirutrun.city

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The places that make each map what it is (the Al-Amin Mosque, the clock towers, the Martyrs'
 * statue, Pigeon Rocks...), for the maps: each a small round badge with a picture of what it is,
 * and on the full map its name under it.
 */
object MapLandmarks {

    enum class Kind(val color: Int) {
        MOSQUE(0xFF2E78C2.toInt()),
        CLOCK(0xFFB0874E.toInt()),
        EGG(0xFF8E8C86.toInt()),
        TOWER(0xFF55524D.toInt()),
        LIGHTHOUSE(0xFF1E1E1E.toInt()),
        STATUE(0xFF7A5C34.toInt()),
        COLUMNS(0xFFA89B7C.toInt()),
        ROCKS(0xFF2B8C9E.toInt()),
    }

    class Landmark(val kind: Kind, val x: Float, val z: Float, val name: String)

    /** [city]'s landmarks, from its buildings (see CityMap's landmark kinds); the biggest sea rock is Pigeon Rocks. */
    fun of(city: CityMap): List<Landmark> {
        val list = ArrayList<Landmark>()
        for (b in city.buildings) {
            val (kind, name) = when (b.kind) {
                CityMap.BUILDING_GRAND_MOSQUE -> Kind.MOSQUE to "Al-Amin Mosque"
                CityMap.BUILDING_CLOCK_TOWER -> Kind.CLOCK to "Al-Abed Clock Tower"
                CityMap.BUILDING_HAMIDIYYEH -> Kind.CLOCK to "Hamidiyyeh Clock Tower"
                CityMap.BUILDING_COLLEGE_HALL -> Kind.CLOCK to "College Hall (AUB)"
                CityMap.BUILDING_EGG -> Kind.EGG to "The Egg"
                CityMap.BUILDING_MURR -> Kind.TOWER to "Murr Tower"
                CityMap.BUILDING_HOLIDAY_INN -> Kind.TOWER to "Holiday Inn"
                CityMap.BUILDING_LIGHTHOUSE -> Kind.LIGHTHOUSE to "Lighthouse"
                CityMap.BUILDING_STATUE -> Kind.STATUE to "Martyrs' Statue"
                CityMap.BUILDING_COLUMNS -> Kind.COLUMNS to "Roman Baths"
                else -> continue
            }
            list += Landmark(kind, b.centerX, b.centerZ, name)
        }
        city.buildings.filter { it.kind == CityMap.BUILDING_ROCK && it.area > 1500f }.maxByOrNull { it.area }?.let {
            list += Landmark(Kind.ROCKS, it.centerX, it.centerZ, "Pigeon Rocks")
        }
        return list
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** A badge for [l] centred at (cx, cy), [r] pixels in radius: a dark ring, the colour, the picture in white. */
    fun draw(c: Canvas, l: Landmark, cx: Float, cy: Float, r: Float, label: Boolean, density: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF151515.toInt()
        c.drawCircle(cx, cy, r + density * 1.2f, paint)
        paint.color = l.kind.color
        c.drawCircle(cx, cy, r, paint)
        paint.color = 0xFFFFFFFF.toInt()
        // The picture is drawn on a 2 × 2 square round the centre, scaled to the badge.
        c.save()
        c.translate(cx, cy)
        c.scale(r * 0.62f, r * 0.62f)
        when (l.kind) {
            Kind.MOSQUE -> mosque(c)
            Kind.CLOCK -> clock(c)
            Kind.EGG -> egg(c)
            Kind.TOWER -> tower(c)
            Kind.LIGHTHOUSE -> lighthouse(c)
            Kind.STATUE -> statue(c)
            Kind.COLUMNS -> columns(c)
            Kind.ROCKS -> rocks(c)
        }
        c.restore()
        if (label) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 10.5f * density
            paint.isFakeBoldText = true
            val y = cy + r + 12f * density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = 0xCC000000.toInt()
            c.drawText(l.name, cx, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            c.drawText(l.name, cx, y, paint)
            paint.isFakeBoldText = false
        }
    }

    /** A dome between two minarets with pointed caps. */
    private fun mosque(c: Canvas) {
        c.drawArc(RectF(-0.7f, -0.55f, 0.7f, 0.85f), 180f, 180f, true, paint)
        c.drawRect(-0.75f, 0.15f, 0.75f, 0.9f, paint)
        for (x in floatArrayOf(-1.05f, 1.05f)) {
            c.drawRect(x - 0.13f, -0.7f, x + 0.13f, 0.9f, paint)
            path.reset(); path.moveTo(x - 0.17f, -0.7f); path.lineTo(x, -1.1f); path.lineTo(x + 0.17f, -0.7f); path.close()
            c.drawPath(path, paint)
        }
        // The crescent's finial on the dome.
        c.drawRect(-0.04f, -0.85f, 0.04f, -0.55f, paint)
    }

    /** A clock face at ten past ten. */
    private fun clock(c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.22f
        c.drawCircle(0f, 0f, 0.85f, paint)
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(0f, 0f, 0f, -0.6f, paint)
        c.drawLine(0f, 0f, 0.45f, 0.15f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    /** The Egg: an oval dome on the ground. */
    private fun egg(c: Canvas) {
        c.drawArc(RectF(-1f, -0.85f, 1f, 1.05f), 180f, 180f, true, paint)
        c.drawRect(-1f, 0.1f, 1f, 0.35f, paint)
    }

    /** A tall block with rows of windows (the Murr Tower, the Holiday Inn). */
    private fun tower(c: Canvas) {
        c.drawRect(-0.5f, -1f, 0.5f, 1f, paint)
        paint.color = 0xFF55524D.toInt()
        for (row in 0 until 5) for (col in 0 until 2) {
            val x = -0.32f + col * 0.4f; val y = -0.8f + row * 0.36f
            c.drawRect(x, y, x + 0.24f, y + 0.18f, paint)
        }
        paint.color = 0xFFFFFFFF.toInt()
    }

    /** A tapering tower in bands under its lantern. */
    private fun lighthouse(c: Canvas) {
        path.reset(); path.moveTo(-0.45f, 1f); path.lineTo(-0.3f, -0.45f); path.lineTo(0.3f, -0.45f); path.lineTo(0.45f, 1f); path.close()
        c.drawPath(path, paint)
        paint.color = 0xFF1E1E1E.toInt()
        c.drawRect(-0.42f, 0.35f, 0.42f, 0.65f, paint)
        c.drawRect(-0.37f, -0.15f, 0.37f, 0.12f, paint)
        paint.color = 0xFFFFD54F.toInt()
        c.drawRect(-0.22f, -0.8f, 0.22f, -0.45f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        path.reset(); path.moveTo(-0.3f, -0.8f); path.lineTo(0f, -1.05f); path.lineTo(0.3f, -0.8f); path.close()
        c.drawPath(path, paint)
    }

    /** A figure on a pedestal holding a torch high. */
    private fun statue(c: Canvas) {
        c.drawRect(-0.7f, 0.65f, 0.7f, 1f, paint)
        c.drawRect(-0.45f, 0.4f, 0.45f, 0.65f, paint)
        c.drawCircle(0f, -0.45f, 0.17f, paint)
        path.reset(); path.moveTo(-0.22f, -0.25f); path.lineTo(0.22f, -0.25f); path.lineTo(0.3f, 0.4f); path.lineTo(-0.3f, 0.4f); path.close()
        c.drawPath(path, paint)
        paint.strokeWidth = 0.13f
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(0.15f, -0.2f, 0.45f, -0.85f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = 0xFFFFB300.toInt()
        c.drawCircle(0.48f, -0.95f, 0.13f, paint)
        paint.color = 0xFFFFFFFF.toInt()
    }

    /** Three columns under a lintel. */
    private fun columns(c: Canvas) {
        c.drawRect(-1f, -0.85f, 1f, -0.6f, paint)
        c.drawRect(-1f, 0.75f, 1f, 0.95f, paint)
        for (x in floatArrayOf(-0.65f, 0f, 0.65f)) c.drawRect(x - 0.16f, -0.6f, x + 0.16f, 0.75f, paint)
    }

    /** A rock with an arch through it, rising from the waves. */
    private fun rocks(c: Canvas) {
        path.reset()
        path.moveTo(-0.95f, 0.55f); path.lineTo(-0.75f, -0.5f); path.lineTo(-0.3f, -0.85f); path.lineTo(0.35f, -0.7f)
        path.lineTo(0.7f, -0.2f); path.lineTo(0.8f, 0.55f); path.close()
        c.drawPath(path, paint)
        // The arch.
        paint.color = 0xFF2B8C9E.toInt()
        c.drawArc(RectF(-0.4f, -0.05f, 0.25f, 0.9f), 180f, 180f, true, paint)
        c.drawRect(-0.4f, 0.42f, 0.25f, 0.6f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        // Waves.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.12f
        path.reset(); path.moveTo(-1f, 0.8f); path.quadTo(-0.75f, 0.65f, -0.5f, 0.8f); path.quadTo(-0.25f, 0.95f, 0f, 0.8f)
        path.quadTo(0.25f, 0.65f, 0.5f, 0.8f); path.quadTo(0.75f, 0.95f, 1f, 0.8f)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
