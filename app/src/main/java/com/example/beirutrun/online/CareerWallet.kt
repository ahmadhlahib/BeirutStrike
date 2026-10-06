package com.example.beirutrun.online

import android.content.Context
import android.util.Log
import com.example.beirutrun.Session
import com.example.beirutrun.progression.Wallet
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ServerValue

/**
 * Copies the player's money and guns (see [Wallet]) to their online career, `career/{uid}/cash`
 * and `career/{uid}/guns`, and matches them up when the player signs in ([settle]).
 */
object CareerWallet {
    private const val TAG = "CareerWallet"

    /** Writes this phone's balance and guns to the career, if signed in (otherwise nothing happens). */
    fun upload(context: Context) {
        if (!FirebaseSession.configured(context)) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val database = FirebaseSession.database() ?: return
        val (cash, guns) = Wallet.forCareer(context)
        database.reference.updateChildren(mapOf(
            "career/$uid/name" to Session.name(context).orEmpty().take(40),
            "career/$uid/cash" to cash,
            "career/$uid/guns" to guns,
            "career/$uid/updated" to ServerValue.TIMESTAMP,
        )).addOnFailureListener { Log.w(TAG, "Wallet upload failed: ${it.message}") }
    }

    /** After signing in as [uid]: reads the career's wallet, matches it with this phone's, and writes the result back. */
    fun settle(context: Context, uid: String, onDone: () -> Unit = {}) {
        val database = FirebaseSession.database() ?: return
        val app = context.applicationContext
        database.getReference("career/$uid").get()
            .addOnSuccessListener { snap ->
                val cash = (snap.child("cash").value as? Number)?.toLong()
                val guns = snap.child("guns").getValue(String::class.java)
                Wallet.adoptCareer(app, cash, guns)
                upload(app)
                onDone()
            }
            .addOnFailureListener { Log.w(TAG, "Reading the career's wallet failed: ${it.message}") }
    }
}
