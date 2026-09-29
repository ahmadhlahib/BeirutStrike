package com.example.beirutrun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * A photo a player took and dropped in the city. It stands at ([x], [z]) facing the direction the
 * player was looking ([yaw]); the author's character stands beside it saying [caption].
 */
data class PhotoDrop(
    val id: String,
    val x: Float,
    val z: Float,
    val yaw: Float,
    val caption: String,
    val author: String,
    /** Team id of the author (see Teams), for the uniform of the soldier standing by the photo. */
    val team: String,
    val time: Long,
    /** Firebase user id of the author; empty for drops made while playing offline. */
    val authorId: String = "",
)

/** Stores street photos (used on building walls) and dropped photos in the app's private storage. */
class WorldRepository(private val context: Context) {
    private val streetsDir = File(context.filesDir, "streets").apply { mkdirs() }
    private val dropsDir = File(context.filesDir, "drops").apply { mkdirs() }
    private val dropsFile = File(context.filesDir, "drops.json")

    // ---- Street photos ----------------------------------------------------------------------

    fun streets(): List<File> =
        streetsDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()

    fun addStreet(uri: Uri) {
        val file = File(streetsDir, "street_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    fun removeAllStreets() {
        streets().forEach { it.delete() }
    }

    // ---- Dropped photos ---------------------------------------------------------------------

    fun photoFile(id: String) = File(dropsDir, "$id.jpg")

    /** Where the camera app writes a new picture before it is dropped. */
    fun captureFile(): File =
        File(File(context.cacheDir, "capture").apply { mkdirs() }, "capture.jpg")

    /** Turns a fresh camera picture into a reasonably sized, upright photo for a new drop. */
    fun importCapture(capture: File, id: String): Boolean {
        val bitmap = decodeScaled(capture, 1600) ?: return false
        photoFile(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        capture.delete()
        return true
    }

    fun drops(): MutableList<PhotoDrop> {
        if (!dropsFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(dropsFile.readText())
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                PhotoDrop(
                    id = o.getString("id"),
                    x = o.getDouble("x").toFloat(),
                    z = o.getDouble("z").toFloat(),
                    yaw = o.getDouble("yaw").toFloat(),
                    caption = o.optString("caption"),
                    author = o.optString("author"),
                    team = o.optString("team"),
                    time = o.optLong("time"),
                )
            }
        }.getOrElse { mutableListOf() }
    }

    fun saveDrops(drops: List<PhotoDrop>) {
        val array = JSONArray()
        drops.forEach {
            array.put(JSONObject()
                .put("id", it.id)
                .put("x", it.x.toDouble())
                .put("z", it.z.toDouble())
                .put("yaw", it.yaw.toDouble())
                .put("caption", it.caption)
                .put("author", it.author)
                .put("team", it.team)
                .put("time", it.time))
        }
        dropsFile.writeText(array.toString())
    }

    fun deleteDrop(drops: MutableList<PhotoDrop>, drop: PhotoDrop) {
        drops.removeAll { it.id == drop.id }
        photoFile(drop.id).delete()
        saveDrops(drops)
    }

    /** Deletes every photo on this phone: drops (mine and downloaded ones) and street photos. */
    fun deleteAllPhotos() {
        dropsDir.listFiles()?.forEach { it.delete() }
        dropsFile.delete()
        removeAllStreets()
    }

    companion object {
        /** Decodes an image no bigger than [maxSize] on its longest side, rotated upright per EXIF. */
        fun decodeScaled(file: File, maxSize: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= maxSize) sample *= 2
            var bitmap = BitmapFactory.decodeFile(
                file.path, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null

            val scale = maxSize / max(bitmap.width, bitmap.height).toFloat()
            val degrees = runCatching {
                when (ExifInterface(file.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            if (degrees == 0f && scale >= 1f) return bitmap

            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(degrees)
            }
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            bitmap = transformed
            return bitmap
        }
    }
}
