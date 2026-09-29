package com.example.beirutrun.online

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors

/**
 * Other players' face photos on this phone (`faces_remote/{uid}.img`), downloaded from
 * `faces/{uid}`. [OnlineWorld] keeps the faces of players in the city up to date; screens like
 * the ranking use [fetchIfMissing] for players who aren't around.
 */
object FaceStore {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Faces being fetched, or found missing on the server, this run (so each is asked for once). */
    private val asked = HashSet<String>()

    fun file(context: Context, uid: String): File =
        File(File(context.filesDir, "faces_remote").apply { mkdirs() }, "$uid.img")

    /** Downloads [uid]'s face if this phone has never had it; [onSaved] runs (main thread) once it's on disk. */
    fun fetchIfMissing(context: Context, uid: String, onSaved: () -> Unit) {
        val file = file(context, uid)
        if (file.exists() || !asked.add(uid)) return
        val database = FirebaseSession.database() ?: return
        database.getReference("faces/$uid/data").get().addOnSuccessListener { snap ->
            val data = snap.getValue(String::class.java) ?: return@addOnSuccessListener
            io.execute {
                val ok = runCatching { file.writeBytes(Base64.decode(data, Base64.DEFAULT)) }.isSuccess
                if (ok) main.post(onSaved)
            }
        }
    }
}
