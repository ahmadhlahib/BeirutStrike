package com.example.beirutrun.online

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.example.beirutrun.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth

/**
 * Forced updates. `config/minVersion` in the database is the oldest versionCode still allowed to
 * play; older versions get a screen that only lets them update ([check]). Each phone also records
 * its versionCode at `versions/{uid}` ([register]), and the database rules only let an up-to-date
 * version create or join rooms, so even versions from before this check (which can't show the
 * screen) are kept out of online games. With no `config/minVersion`, nothing is blocked.
 */
object AppVersion {
    private const val TAG = "AppVersion"

    fun code(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    /** Records this phone's version for the signed-in player (the rules check it when joining rooms). */
    fun register(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val database = FirebaseSession.database() ?: return
        database.getReference("versions/$uid").setValue(code(context))
            .addOnFailureListener { Log.w(TAG, "Recording the version failed: ${it.message}") }
    }

    /**
     * Reads the oldest version allowed; if this one is older, shows the update screen (which can't
     * be dismissed). Without a connection, or with no minimum set, nothing happens.
     */
    fun check(activity: Activity) {
        val database = FirebaseSession.database() ?: return
        database.getReference("config/minVersion").get()
            .addOnSuccessListener { snap ->
                val min = (snap.value as? Number)?.toLong() ?: return@addOnSuccessListener
                if (code(activity) < min && !activity.isFinishing) showUpdate(activity)
            }
            .addOnFailureListener { Log.w(TAG, "Reading the minimum version failed: ${it.message}") }
    }

    private fun showUpdate(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_message)
            .setCancelable(false)
            .setPositiveButton(R.string.update_button) { _, _ ->
                openStore(activity)
                // Still not updated when they come back: the screen again.
                activity.finishAffinity()
            }
            .setNegativeButton(R.string.update_close) { _, _ -> activity.finishAffinity() }
            .show()
    }

    /** The game's page on Google Play (in the Play Store app if there is one). */
    fun openStore(activity: Activity) {
        val id = activity.packageName
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
        }
    }
}
