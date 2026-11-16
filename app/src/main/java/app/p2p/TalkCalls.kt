package app.p2p

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

internal const val TALK_SIGNAL = "talkCall"
internal const val TALK_RING_MS = 45_000L
internal const val MISSED_CALL_WINDOW = 24 * 60 * 60_000L
internal const val TALK_CONNECT_MS = 30_000L

internal data class TalkSignal(val call: String, val action: String, val expires: Long, val sdp: String = "") {
    fun packet() = obj("type" to TALK_SIGNAL, "id" to newId(), "call" to call,
        "action" to action, "expires" to expires, "sdp" to sdp)

    companion object {
        fun read(data: JSONObject, now: Long, allowMissedOffer: Boolean = false): TalkSignal? {
            val call = data.optString("call")
            val action = data.optString("action")
            val expires = data.optLong("expires")
            val sdp = data.optString("sdp")
            if (!isId(call) || expires <= 0 || expires > now + TALK_RING_MS + 15_000) return null
            if (expires <= now && !(allowMissedOffer && action == "offer" && expires > now - MISSED_CALL_WINDOW)) return null
            val valid = when (action) {
                "offer", "answer" -> validTalkSdp(sdp)
                "decline", "end", "busy" -> sdp.isEmpty()
                else -> false
            }
            return if (valid) TalkSignal(call, action, expires, sdp) else null
        }
    }
}

internal enum class TalkPhase { Connecting, Incoming, Active, Finished }

internal enum class TalkSetupStep(val label: String) {
    FindingRoute("Finding a route…"),
    SendingInvite("Sending invite…"),
    WaitingForAnswer("Waiting for answer…"),
    ConnectingAudio("Connecting audio…")
}

internal data class TalkCall(
    val id: String,
    val contact: String,
    val phase: TalkPhase,
    val outgoing: Boolean,
    val speaker: Boolean = false,
    val muted: Boolean = false,
    val connectedAt: Long = 0,
    val detail: String = "",
    val setupStep: TalkSetupStep = TalkSetupStep.FindingRoute
)

internal class TalkCalls(
    private val context: Context,
    private val scope: CoroutineScope,
    private val signal: suspend (String, TalkSignal) -> Unit,
    private val ice: suspend () -> String,
    private val incomingAlert: suspend (String, String, Long) -> Unit,
    private val clearAlert: (String) -> Unit,
    private val missedCall: suspend (TalkCall) -> Unit = {},
    private val completedCall: suspend (TalkCall) -> Unit = {}
) {
    private val current = MutableStateFlow<TalkCall?>(null)
    val state = current.asStateFlow()
    private var media: TalkAudio? = null
    private var task: Job? = null
    private var timeout: Job? = null
    private var ringtone: Ringtone? = null
    private var offer = ""
    private var ringUntil = 0L
    private var awaitingAnswer = false
    private val recentlyEnded = mutableMapOf<Pair<String, String>, Long>()

    fun start(contact: String) {
        if (current.value?.phase?.let { it != TalkPhase.Finished } == true) return
        AppLog.record(context, "Talk: outgoing call started")
        val call = TalkCall(newId(), contact, TalkPhase.Connecting, outgoing = true)
        current.value = call
        awaitingAnswer = true
        ringUntil = 0L
        prepare(call, null)
    }

    fun ring() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Incoming } ?: return
        clearAlert(call.id)
        if (ringtone != null || System.currentTimeMillis() >= ringUntil) return
        runCatching {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply { isLooping = true; play() }
        }
    }

    fun silence() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    fun accept(id: String) {
        val call = current.value?.takeIf { it.phase == TalkPhase.Incoming && it.id == id } ?: return
        val sdp = offer
        if (System.currentTimeMillis() >= ringUntil || sdp.isEmpty()) {
            finish("Missed call", null)
            return
        }
        silence()
        clearAlert(call.id)
        current.value = call.copy(phase = TalkPhase.Connecting, setupStep = TalkSetupStep.ConnectingAudio)
        timeout?.cancel()
        prepare(call, sdp)
    }

    private fun prepare(call: TalkCall, remoteOffer: String?) {
        task = scope.launch {
            try {
                withTimeout(25_000) {
                    TalkCallService.ensure(context, call.id)
                    val servers = ice()
                    if (!isCurrent(call)) return@withTimeout
                    val audio = TalkAudio(context, servers, { state -> scope.launch { connectionChanged(call, state) } },
                        { scope.launch { if (isCurrent(call)) finish("Audio was interrupted", "end") } },
                        { scope.launch { if (isCurrent(call)) finish("Couldn’t start call audio", "end") } })
                    media = audio
                    val sdp = audio.prepare(remoteOffer)
                    if (!isCurrent(call)) return@withTimeout
                    if (remoteOffer == null) {
                        ringUntil = System.currentTimeMillis() + TALK_RING_MS
                        startTimer(call, TALK_RING_MS)
                    } else if (current.value?.phase == TalkPhase.Connecting) startTimer(call, TALK_CONNECT_MS)
                    val expires = if (remoteOffer == null) ringUntil else System.currentTimeMillis() + TALK_CONNECT_MS
                    if (remoteOffer == null) advanceSetup(call.id, TalkSetupStep.SendingInvite)
                    send(call, if (remoteOffer == null) "offer" else "answer", sdp, expires)
                }
            } catch (_: TimeoutCancellationException) {
                if (isCurrent(call)) finish("Couldn’t connect", "end")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.record(context, "Talk: call setup failed", error)
                if (isCurrent(call)) finish("Couldn’t connect", "end")
            } catch (_: LinkageError) {
                if (isCurrent(call)) finish("Call audio is unavailable on this device", "end")
            }
        }
    }

    suspend fun receive(contact: String, received: TalkSignal) = withContext(Dispatchers.Main.immediate) {
        val now = System.currentTimeMillis()
        if (received.expires <= now) return@withContext
        AppLog.record(context, "Talk: received ${received.action}")
        recentlyEnded.entries.removeIf { it.value <= now }
        val key = contact to received.call
        if (received.action == "end") recentlyEnded[key] = received.expires
        val active = current.value?.takeIf { it.phase != TalkPhase.Finished }
        if (received.action == "offer") {
            if (key in recentlyEnded) return@withContext
            if (active?.id == received.call) return@withContext
            if (active != null) {
                val crossed = active.contact == contact && active.outgoing && active.phase == TalkPhase.Connecting
                if (!crossed || active.id < received.call) {
                    signal(contact, TalkSignal(received.call, "busy", received.expires))
                    return@withContext
                }
                finish("Call replaced", null, keepResult = false)
            }
            val call = TalkCall(received.call, contact, TalkPhase.Incoming, outgoing = false)
            offer = received.sdp
            ringUntil = received.expires
            current.value = call
            startTimer(call, (ringUntil - System.currentTimeMillis()).coerceAtLeast(1L))
            incomingAlert(contact, call.id, ringUntil)
            return@withContext
        }
        if (active == null || active.id != received.call || active.contact != contact) return@withContext
        when (received.action) {
            "answer" -> if (active.outgoing && awaitingAnswer) {
                advanceSetup(active.id, TalkSetupStep.ConnectingAudio)
                awaitingAnswer = false
                startTimer(active, TALK_CONNECT_MS)
                task = scope.launch {
                    try {
                        withTimeout(8_000) {
                            val audio = checkNotNull(media)
                            audio.applyRemote(SessionDescription.Type.ANSWER, received.sdp)
                        }
                    } catch (_: TimeoutCancellationException) {
                        if (isCurrent(active)) finish("Couldn’t connect", "end")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppLog.record(context, "Talk: could not apply answer", error)
                        if (isCurrent(active)) finish("Couldn’t connect", "end")
                    }
                }
            }
            "busy" -> if (active.outgoing && awaitingAnswer) finish("Contact is busy", null)
            "decline" -> if (active.outgoing && awaitingAnswer) finish("Call declined", null)
            "end" -> finish(if (active.phase == TalkPhase.Incoming) "Missed call" else "Call ended", null)
        }
    }

    private fun connectionChanged(call: TalkCall, state: PeerConnection.PeerConnectionState) {
        if (!isCurrent(call)) return
        AppLog.record(context, "Talk: WebRTC connection $state")
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                timeout?.cancel()
                awaitingAnswer = false
                val latest = current.value ?: return
                if (latest.phase != TalkPhase.Active) {
                    try { media?.activate() } catch (error: Exception) {
                        AppLog.record(context, "Talk: audio activation failed", error)
                        finish("Audio was interrupted", "end")
                        return
                    }
                    current.value = latest.copy(phase = TalkPhase.Active, connectedAt = SystemClock.elapsedRealtime())
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> startTimer(call, 10_000)
            PeerConnection.PeerConnectionState.FAILED -> finish("Connection lost", "end")
            else -> Unit
        }
    }

    private fun startTimer(call: TalkCall, delayMs: Long) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(delayMs)
            if (isCurrent(call)) {
                val detail = when (current.value?.phase) {
                    TalkPhase.Incoming -> "Missed call"
                    TalkPhase.Active -> "Connection lost"
                    else -> if (awaitingAnswer) "No answer" else "Couldn’t connect"
                }
                finish(detail, "end")
            }
        }
    }

    fun toggleMute() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Active } ?: return
        media?.muteMicrophone(!call.muted)
        current.value = call.copy(muted = !call.muted)
    }

    fun toggleSpeaker() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Active } ?: return
        if (media?.speaker(!call.speaker) == true) current.value = call.copy(speaker = !call.speaker)
    }

    fun decline(id: String) {
        if (current.value?.let { it.phase == TalkPhase.Incoming && it.id == id } == true) end()
    }

    fun end() {
        val call = current.value ?: return
        if (call.phase == TalkPhase.Finished) current.value = null
        else finish("Call ended", if (call.phase == TalkPhase.Incoming) "decline" else "end",
            keepResult = call.outgoing)
    }

    fun onAppHidden() {
        silence()
        val call = current.value ?: return
        when (call.phase) {
            TalkPhase.Incoming -> {
                val expiresAt = ringUntil
                scope.launch {
                    if (current.value == call && System.currentTimeMillis() < expiresAt) {
                        incomingAlert(call.contact, call.id, expiresAt)
                    }
                }
            }
            TalkPhase.Active, TalkPhase.Connecting -> Unit
            TalkPhase.Finished -> Unit
        }
    }

    private fun finish(detail: String, response: String?, keepResult: Boolean = true) {
        val call = current.value ?: return
        AppLog.record(context, "Talk: $detail")
        scope.launch { completedCall(call.copy(detail = detail)) }
        task?.cancel()
        timeout?.cancel()
        silence()
        TalkCallService.stop(context)
        val audio = media
        media = null
        runCatching { audio?.close() }
        offer = ""
        awaitingAnswer = false
        clearAlert(call.id)
        recentlyEnded[call.contact to call.id] = System.currentTimeMillis() + TALK_RING_MS
        current.value = if (keepResult) call.copy(phase = TalkPhase.Finished, speaker = false, detail = detail) else null
        if (call.phase == TalkPhase.Incoming && detail == "Missed call") scope.launch { missedCall(call) }
        if (response != null) scope.launch {
            try {
                send(call, response, "", System.currentTimeMillis() + TALK_RING_MS)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The remote invitation expires even when signaling is unavailable.
            }
        }
    }

    internal fun inviteSent(id: String) {
        scope.launch(Dispatchers.Main.immediate) {
            if (current.value?.let { it.id == id && it.setupStep < TalkSetupStep.WaitingForAnswer } == true) {
                AppLog.record(context, "Talk: invite delivered")
            }
            advanceSetup(id, TalkSetupStep.WaitingForAnswer)
        }
    }

    private fun advanceSetup(id: String, step: TalkSetupStep) {
        val latest = current.value ?: return
        if (latest.id == id && latest.phase == TalkPhase.Connecting && step > latest.setupStep) {
            current.value = latest.copy(setupStep = step)
        }
    }

    private fun isCurrent(call: TalkCall) = current.value?.let { it.id == call.id && it.phase != TalkPhase.Finished } == true

    private suspend fun send(call: TalkCall, action: String, sdp: String, expires: Long) {
        signal(call.contact, TalkSignal(call.id, action, expires, sdp))
        AppLog.record(context, "Talk: $action queued")
    }
}
