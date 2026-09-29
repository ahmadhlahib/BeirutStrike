package com.example.beirutrun.online

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/** Firebase set-up shared by the rooms screen and the city: sign-in and the database handle. */
object FirebaseSession {
    private const val TAG = "FirebaseSession"
    private var persistenceSet = false

    /** False when the app was built without google-services.json (single-phone mode). */
    fun configured(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

    /** Signs in anonymously if needed; [onResult] gets the user id, or null on failure. */
    fun signIn(onResult: (String?) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return onResult(it.uid) }
        auth.signInAnonymously()
            .addOnSuccessListener { onResult(it.user?.uid) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Anonymous sign-in failed", e)
                onResult(null)
            }
    }

    /** The Realtime Database, with offline caching turned on the first time; null if unavailable. */
    fun database(): FirebaseDatabase? = try {
        FirebaseDatabase.getInstance().also {
            if (!persistenceSet) {
                persistenceSet = true
                runCatching { it.setPersistenceEnabled(true) }
            }
        }
    } catch (e: Exception) {
        // Usually google-services.json was downloaded before the database was created,
        // so it has no database URL.
        Log.e(TAG, "Realtime Database unavailable", e)
        null
    }
}
