package com.example.beirutrun.online

import android.app.Activity
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.beirutrun.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Signing in with a Google account, so the player's career (XP, rank, money and guns) follows
 * them to another phone. Players can also play as a guest (an anonymous account, see
 * [FirebaseSession.signIn]); signing in then keeps the guest's progress by attaching the Google
 * account to it, unless that Google account already has a game account of its own, which is
 * switched to instead.
 */
object GoogleAccount {
    private const val TAG = "GoogleAccount"

    enum class Outcome {
        /** The guest account now has the Google account: same player, nothing lost. */
        LINKED,
        /** Signed in to the Google account's own game account (a different player id). */
        SWITCHED,
        CANCELLED,
        FAILED,
    }

    /** The signed-in Google account's email, or null when playing as a guest (or not signed in). */
    fun email(): String? = runCatching {
        FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous }?.email
    }.getOrNull()

    /** The Google account's display name, if signed in with one. */
    fun displayName(): String? = runCatching {
        FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous }?.displayName
    }.getOrNull()

    /** Shows Google's account picker, then signs in to the game with the chosen account. */
    fun signIn(activity: Activity, onDone: (Outcome) -> Unit) {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(activity).getCredentialAsync(
            activity, request, CancellationSignal(), ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = result.credential
                    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        Log.w(TAG, "Unexpected credential: ${credential.type}")
                        return onDone(Outcome.FAILED)
                    }
                    val token = runCatching { GoogleIdTokenCredential.createFrom(credential.data).idToken }.getOrNull()
                        ?: return onDone(Outcome.FAILED)
                    withFirebase(token, onDone)
                }

                override fun onError(e: GetCredentialException) {
                    Log.w(TAG, "Google sign-in: ${e.type}: ${e.message}")
                    onDone(if (e is GetCredentialCancellationException) Outcome.CANCELLED else Outcome.FAILED)
                }
            },
        )
    }

    /** Signs in to Firebase with Google's [idToken]: attached to the guest account if possible. */
    private fun withFirebase(idToken: String, onDone: (Outcome) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val guest = auth.currentUser?.takeIf { it.isAnonymous }
        if (guest == null) {
            auth.signInWithCredential(credential)
                .addOnSuccessListener { onDone(Outcome.SWITCHED) }
                .addOnFailureListener { Log.w(TAG, "Sign-in failed", it); onDone(Outcome.FAILED) }
            return
        }
        guest.linkWithCredential(credential)
            .addOnSuccessListener { onDone(Outcome.LINKED) }
            .addOnFailureListener { e ->
                if (e !is FirebaseAuthUserCollisionException) {
                    Log.w(TAG, "Linking failed", e)
                    return@addOnFailureListener onDone(Outcome.FAILED)
                }
                // This Google account already plays (on another phone): switch to it.
                auth.signInWithCredential(credential)
                    .addOnSuccessListener { onDone(Outcome.SWITCHED) }
                    .addOnFailureListener { Log.w(TAG, "Sign-in failed", it); onDone(Outcome.FAILED) }
            }
    }

    /** Signs out of the Google account (back to a fresh guest next time), and forgets the account picker's choice. */
    fun signOut(activity: Activity) {
        FirebaseAuth.getInstance().signOut()
        CredentialManager.create(activity).clearCredentialStateAsync(
            ClearCredentialStateRequest(), CancellationSignal(), ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<Void?, ClearCredentialException> {
                override fun onResult(result: Void?) = Unit
                override fun onError(e: ClearCredentialException) { Log.w(TAG, "Clearing credentials: ${e.message}") }
            },
        )
    }
}
