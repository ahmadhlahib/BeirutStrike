package com.example.beirutrun.online

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.beirutrun.Blocklist
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID

/**
 * Team voice chat. Each phone talks straight to each teammate's phone (WebRTC, audio only), so
 * there is no voice server to pay for. Firebase only carries the short setup messages, in
 * `rooms/{room}/voice/{to}/{id}`, and the rules only let teammates write to each other.
 *
 * Of each pair, the phone with the smaller uid makes the offer. A connection that doesn't come up
 * (or drops) is started again by [check]; one to a player who left the team is closed.
 *
 * The mic starts off and is only recorded while on. [setAllMuted] and [setMuted] only change what
 * I hear. Blocked players are never connected. All calls on the main thread.
 */
class VoiceChat(context: Context, private val roomId: String) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("voice", Context.MODE_PRIVATE)

    /** Whether my teammates can hear me. */
    var micOn = false
        private set

    /** Whether I've silenced all my teammates. */
    var allMuted = prefs.getBoolean(KEY_ALL_MUTED, false)
        private set

    private var running = false
    private var myUid: String? = null
    private var inbox: DatabaseReference? = null
    private var inboxListener: ChildEventListener? = null
    private var teammates: Set<String> = emptySet()
    private val peers = HashMap<String, Peer>()

    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    private val check = object : Runnable {
        override fun run() {
            syncPeers()
            main.postDelayed(this, CHECK_MS)
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    /** Joins the team's voice chat as [uid] (once signed in). Does nothing if already started. */
    fun start(uid: String) {
        if (running) return
        val database = FirebaseSession.database() ?: return
        running = true
        myUid = uid
        val ref = database.getReference("rooms/$roomId/voice/$uid")
        inbox = ref
        ref.onDisconnect().removeValue()
        // Messages left from before (a crash, another game) would only confuse new connections.
        ref.removeValue().addOnCompleteListener { if (running && inbox === ref) listen(ref) }
        main.post(check)
    }

    /** Leaves the voice chat (the app went to the background, or the city closed): mic off. */
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(check)
        inboxListener?.let { inbox?.removeEventListener(it) }
        inboxListener = null
        inbox?.removeValue()
        inbox = null
        for (p in peers.values) p.close()
        peers.clear()
        micOn = false
        micTrack?.dispose()
        micTrack = null
        micSource?.dispose()
        micSource = null
        factory?.dispose()
        factory = null
        audioModule?.release()
        audioModule = null
    }

    /** The teammates in the room right now (their uids, not mine). */
    fun setTeammates(uids: Set<String>) {
        teammates = uids
        syncPeers()
    }

    // ---- Mic and muting -----------------------------------------------------------------------

    /** Turns my mic on or off for all my teammates. Needs the RECORD_AUDIO permission to turn on. */
    fun setMic(on: Boolean) {
        if (on == micOn) return
        micOn = on
        micTrack?.setEnabled(on)
        for (p in peers.values) {
            p.pc.setAudioRecording(on)
            attachMic(p)
        }
    }

    fun setAllMuted(muted: Boolean) {
        allMuted = muted
        prefs.edit().putBoolean(KEY_ALL_MUTED, muted).apply()
        for (p in peers.values) applyMute(p)
    }

    fun isMuted(uid: String) = uid in mutedPlayers()

    /** Mutes (or unmutes) one player's voice on this phone; remembered for later games. */
    fun setMuted(uid: String, muted: Boolean) {
        val set = mutedPlayers().toMutableSet()
        if (muted) set += uid else set -= uid
        prefs.edit().putStringSet(KEY_MUTED, set).apply()
        peers[uid]?.let(::applyMute)
    }

    private fun mutedPlayers(): Set<String> = prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty()

    private fun applyMute(p: Peer) {
        p.remoteTrack?.setEnabled(!allMuted && !isMuted(p.uid))
    }

    /** Sends my mic to [p] while it's on; nothing (and no recording) while it's off. */
    private fun attachMic(p: Peer) {
        val track = if (micOn) micTrack() else null
        p.pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
            ?.sender?.setTrack(track, false)
    }

    private fun micTrack(): AudioTrack = micTrack ?: run {
        val source = factory().createAudioSource(MediaConstraints())
        micSource = source
        factory().createAudioTrack("mic", source).also { micTrack = it }
    }

    // ---- Connections --------------------------------------------------------------------------

    /** Calls teammates I should call, restarts stuck calls and hangs up on players who left. */
    private fun syncPeers() {
        val me = myUid ?: return
        if (!running) return
        val wanted = teammates.filterNot { it == me || Blocklist.isBlocked(app, it) }.take(MAX_PEERS).toSet()
        val now = SystemClock.elapsedRealtime()
        // A peer that just called me may not be in my player list yet: give it a moment.
        for (uid in peers.keys.filter { it !in wanted && now - peers.getValue(it).createdAt > GRACE_MS }) {
            peers.remove(uid)?.close()
        }
        for (uid in wanted) {
            val p = peers[uid]
            val broken = p != null && (p.state == PeerConnection.PeerConnectionState.FAILED ||
                p.state == PeerConnection.PeerConnectionState.CLOSED ||
                (p.state != PeerConnection.PeerConnectionState.CONNECTED && now - p.stateSince > STUCK_MS))
            if (me < uid) {
                if (p == null || broken) {
                    p?.close()
                    call(uid)
                }
            } else if (broken) {
                // The other phone makes the offer; it will call again.
                peers.remove(uid)
                p?.close()
            }
        }
    }

    private fun call(uid: String) {
        val p = newPeer(uid, UUID.randomUUID().toString().take(12), offerer = true) ?: return
        p.pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV),
        )
        attachMic(p)
        p.pc.createOffer(Sdp(onCreate = { offer ->
            p.pc.setLocalDescription(Sdp(onSet = {
                main.post { if (!p.closed) send(uid, "offer", p.session, mapOf("sdp" to offer.description)) }
            }), offer)
        }), MediaConstraints())
    }

    private fun answer(uid: String, session: String, sdp: String) {
        val old = peers[uid]
        if (old != null && old.session == session) return
        old?.close()
        val p = newPeer(uid, session, offerer = false) ?: return
        p.pc.setRemoteDescription(Sdp(onSet = {
            main.post {
                if (p.closed) return@post
                p.remoteReady()
                // Send as well as receive, so the mic can be turned on later without a new offer.
                for (t in p.pc.transceivers) t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                attachMic(p)
                p.pc.createAnswer(Sdp(onCreate = { answer ->
                    p.pc.setLocalDescription(Sdp(onSet = {
                        main.post { if (!p.closed) send(uid, "answer", session, mapOf("sdp" to answer.description)) }
                    }), answer)
                }), MediaConstraints())
            }
        }), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun newPeer(uid: String, session: String, offerer: Boolean): Peer? {
        val p = try {
            Peer(uid, session, offerer)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Couldn't create a voice connection", e)
            return null
        }
        peers[uid] = p
        p.pc.setAudioRecording(micOn)
        return p
    }

    // ---- Signalling through Firebase ----------------------------------------------------------

    private fun listen(ref: DatabaseReference) {
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                onMessage(snapshot)
                snapshot.ref.removeValue()
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Voice messages cancelled: ${error.message}")
            }
        }
        ref.addChildEventListener(l)
        inboxListener = l
    }

    private fun onMessage(s: DataSnapshot) {
        if (!running) return
        val from = s.child("from").getValue(String::class.java) ?: return
        val session = s.child("session").getValue(String::class.java) ?: return
        if (from == myUid || Blocklist.isBlocked(app, from)) return
        val sdp = s.child("sdp").getValue(String::class.java).orEmpty()
        when (s.child("type").getValue(String::class.java)) {
            "offer" -> answer(from, session, sdp)
            "answer" -> {
                val p = peers[from]?.takeIf { it.session == session && it.offerer && !it.closed } ?: return
                p.pc.setRemoteDescription(Sdp(onSet = { main.post { if (!p.closed) p.remoteReady() } }),
                    SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice" -> {
                val p = peers[from]?.takeIf { it.session == session && !it.closed } ?: return
                val mid = s.child("mid").getValue(String::class.java)
                val index = (s.child("index").value as? Number)?.toInt() ?: 0
                p.addIce(IceCandidate(mid, index, sdp))
            }
        }
    }

    private fun send(to: String, type: String, session: String, extra: Map<String, Any?>) {
        val me = myUid ?: return
        val database = FirebaseSession.database() ?: return
        database.getReference("rooms/$roomId/voice/$to").push()
            .setValue(mapOf("from" to me, "type" to type, "session" to session) + extra)
    }

    // ---- WebRTC -------------------------------------------------------------------------------

    private fun factory(): PeerConnectionFactory = factory ?: run {
        if (!initialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            initialized = true
        }
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            // Teammates come out of the loudspeaker with the game's sounds, at the media volume.
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .createAudioDeviceModule()
        audioModule = adm
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
            .also { factory = it }
    }

    /** One teammate's connection. [session] tells its messages apart from an earlier attempt's. */
    private inner class Peer(val uid: String, val session: String, val offerer: Boolean) {
        val createdAt = SystemClock.elapsedRealtime()
        var state = PeerConnection.PeerConnectionState.NEW
        var stateSince = createdAt
        var remoteTrack: AudioTrack? = null
        var closed = false
        private var remoteSet = false
        private val pendingIce = mutableListOf<IceCandidate>()

        val pc: PeerConnection = factory().createPeerConnection(
            PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    main.post {
                        if (!closed) send(uid, "ice", session, mapOf("sdp" to c.sdp, "mid" to c.sdpMid, "index" to c.sdpMLineIndex))
                    }
                }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    main.post {
                        if (closed) return@post
                        state = s
                        stateSince = SystemClock.elapsedRealtime()
                    }
                }
                override fun onTrack(t: RtpTransceiver) {
                    main.post {
                        if (closed) return@post
                        remoteTrack = t.receiver.track() as? AudioTrack
                        applyMute(this@Peer)
                    }
                }
                override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
                override fun onAddStream(s: MediaStream) = Unit
                override fun onRemoveStream(s: MediaStream) = Unit
                override fun onDataChannel(d: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
            },
        ) ?: throw IllegalStateException("createPeerConnection returned null")

        /** The other side's description is set: candidates can go in now. */
        fun remoteReady() {
            remoteSet = true
            pendingIce.forEach(pc::addIceCandidate)
            pendingIce.clear()
        }

        fun addIce(c: IceCandidate) {
            if (remoteSet) pc.addIceCandidate(c) else pendingIce += c
        }

        fun close() {
            if (closed) return
            closed = true
            pc.dispose()
        }
    }

    private class Sdp(
        private val onCreate: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = onCreate(d)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) { Log.w(TAG, "Voice SDP create failed: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "Voice SDP set failed: $error") }
    }

    private companion object {
        const val TAG = "VoiceChat"
        const val KEY_ALL_MUTED = "allMuted"
        const val KEY_MUTED = "muted"
        const val CHECK_MS = 5_000L
        /** A connection not up this long after its last change is started again. */
        const val STUCK_MS = 15_000L
        const val GRACE_MS = 10_000L
        /** Every teammate is a separate connection; more than this would use too much data. */
        const val MAX_PEERS = 8
        /** Google's free public STUN server, so phones behind home routers can find each other. */
        val ICE_SERVERS = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        var initialized = false
    }
}
