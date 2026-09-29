package com.example.beirutrun

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.example.beirutrun.online.FaceStore
import java.io.File

/** Fills in a `view_face_badge` layout: the player's face (or a placeholder) and their Commander star. */
object FaceBadge {
    /** Small decoded faces, keyed by file and its modification time (so a retaken face shows up). */
    private val cache = LruCache<String, Bitmap>(64)

    /**
     * Shows [uid]'s face: [myUid]'s own local photo, or the downloaded one for anyone else.
     * [onFaceArrived] runs if the face had to be downloaded first, to redraw.
     */
    fun bind(badge: View, uid: String, myUid: String?, careerHits: Int, onFaceArrived: () -> Unit) {
        val context = badge.context
        val file = if (uid == myUid) Session.faceFile(context) else FaceStore.file(context, uid)
        val image = badge.findViewById<ImageView>(R.id.faceImage)
        val face = load(file)
        if (face != null) {
            image.setImageBitmap(face)
        } else {
            image.setImageResource(R.drawable.ic_person)
            if (uid != myUid) FaceStore.fetchIfMissing(context.applicationContext, uid, onFaceArrived)
        }
        badge.findViewById<View>(R.id.faceStar).visibility =
            if (Army.isCommander(careerHits)) View.VISIBLE else View.GONE
    }

    private fun load(file: File): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.path}@${file.lastModified()}"
        cache.get(key)?.let { return it }
        return WorldRepository.decodeScaled(file, FACE_PX)?.also { cache.put(key, it) }
    }

    private const val FACE_PX = 96
}
