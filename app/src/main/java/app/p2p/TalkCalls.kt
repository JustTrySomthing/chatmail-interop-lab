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
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import org.thoughtcrime.securesms.calls.MediaStreamManager
import org.thoughtcrime.securesms.webrtc.WebRTCClient
import org.webrtc.AudioTrack
import org.webrtc.MediaStream
import org.webrtc.VideoTrack

internal const val TALK_RING_MS = 45_000L
internal const val TALK_CONNECT_MS = 30_000L

internal enum class TalkPhase { Connecting, Incoming, Active, Finished }

internal enum class TalkSetupStep(val label: String) {
    FindingRoute("Finding a route…"),
    SendingInvite("Sending invite…"),
    WaitingForAnswer("Waiting for answer…"),
    ConnectingAudio("Connecting call…")
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
    val setupStep: TalkSetupStep = TalkSetupStep.FindingRoute,
    val displayName: String = "",
    val startsWithVideo: Boolean = false,
    val camera: Boolean = false,
    val frontCamera: Boolean = true,
    val switchingCamera: Boolean = false,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
    val remoteCamera: Boolean = false
)

internal data class ChatmailCallEvent(
    val kind: String, val id: Int, val chatId: Int, val sdp: String, val video: Boolean, val thisDevice: Boolean
)

internal fun readChatmailCallEvent(event: JSONObject): ChatmailCallEvent? {
    val kind = event.optString("kind")
    if (kind !in setOf("IncomingCall", "IncomingCallAccepted", "OutgoingCallAccepted", "CallEnded")) return null
    val id = event.optInt("msg_id", event.optInt("msgId"))
    val chat = event.optInt("chat_id", event.optInt("chatId"))
    if (id <= 9 || chat <= 9) return null
    val sdp = when (kind) {
        "IncomingCall" -> event.optString("place_call_info", event.optString("placeCallInfo"))
        "OutgoingCallAccepted" -> event.optString("accept_call_info", event.optString("acceptCallInfo"))
        else -> ""
    }
    if (kind in setOf("IncomingCall", "OutgoingCallAccepted") &&
        (sdp.length !in 1..65_536 || !sdp.startsWith("v=0"))) return null
    return ChatmailCallEvent(kind, id, chat, sdp, event.optBoolean("has_video", event.optBoolean("hasVideo")),
        event.optBoolean("from_this_device", event.optBoolean("fromThisDevice")))
}

internal class TalkCalls(
    private val context: Context,
    private val scope: CoroutineScope,
    private val resolve: suspend (String) -> ChatmailCallContact,
    private val ice: suspend () -> String,
    private val place: suspend (Int, String) -> Int,
    private val answer: suspend (Int, String) -> Unit,
    private val terminate: suspend (Int) -> Unit,
    private val incomingAlert: suspend (String, Long) -> Unit,
    private val clearAlert: (String) -> Unit,
    private val completed: suspend (TalkCall) -> Unit
) {
    private val current = MutableStateFlow<TalkCall?>(null)
    val state = current.asStateFlow()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) finish("Call audio interrupted")
        }.build()
    private var previousMode: Int? = null
    private var previousDevice: AudioDeviceInfo? = null
    private var deferredEvent: ChatmailCallEvent? = null
    private var client: WebRTCClient? = null
    private var media: MediaStreamManager? = null
    private var timer: Job? = null
    private var videoStats: Job? = null
    private var ringtone: Ringtone? = null
    private var chatId = 0
    private var messageId = 0
    private var incomingOffer = ""

    fun start(contact: String) {
        if (busy()) return
        val call = TalkCall(newId(), contact, TalkPhase.Connecting, outgoing = true)
        current.value = call
        scope.launch {
            guarded(call.id) {
                val chat = resolve(contact)
                if (!active(call.id)) return@guarded
                chatId = chat.id
                current.value = current.value?.copy(displayName = chat.name)
                prepare(call.id, null)
            }
        }
    }

    suspend fun event(event: ChatmailCallEvent, caller: ChatmailCallContact? = null) {
        if (messageId == 0 && chatId == event.chatId && current.value?.outgoing == true &&
            event.kind in setOf("OutgoingCallAccepted", "CallEnded")) {
            deferredEvent = event
            return
        }
        when (event.kind) {
            "IncomingCall" -> {
                if (caller == null) return
                if (busy()) {
                    if (messageId != event.id) runCatching { terminate(event.id) }
                    return
                }
                val id = newId()
                chatId = event.chatId
                messageId = event.id
                incomingOffer = event.sdp
                current.value = TalkCall(id, "chatmail:${event.chatId}", TalkPhase.Incoming, outgoing = false,
                    displayName = caller.name, startsWithVideo = event.video, remoteCamera = event.video)
                armTimeout(id, TALK_RING_MS, "Missed call")
                incomingAlert(id, System.currentTimeMillis() + TALK_RING_MS)
            }
            "OutgoingCallAccepted" -> if (messageId == event.id && chatId == event.chatId) {
                current.value?.let {
                    current.value = it.copy(setupStep = TalkSetupStep.ConnectingAudio)
                    armTimeout(it.id, TALK_CONNECT_MS, "Couldn’t connect")
                }
                client?.handleAnswerSdp(event.sdp)
            }
            "IncomingCallAccepted" -> if (messageId == event.id && !event.thisDevice) {
                finish("Answered on another device", sendEnd = false)
            }
            "CallEnded" -> if (messageId == event.id && chatId == event.chatId) {
                finish(if (current.value?.phase == TalkPhase.Incoming) "Missed call" else "Call ended", sendEnd = false)
            }
        }
    }

    fun accept(id: String) {
        val call = current.value?.takeIf { it.id == id && it.phase == TalkPhase.Incoming } ?: return
        val offer = incomingOffer.takeIf { it.isNotBlank() } ?: return
        silence()
        clearAlert(id)
        current.value = call.copy(phase = TalkPhase.Connecting, setupStep = TalkSetupStep.ConnectingAudio)
        scope.launch { guarded(id) { prepare(id, offer) } }
    }

    private suspend fun prepare(id: String, offer: String?) {
        armTimeout(id, TALK_CONNECT_MS, "Couldn’t connect")
        TalkCallService.ensure(context, id)
        val servers = ice()
        if (!active(id)) return
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Call audio is busy." }
        previousMode = audio.mode
        previousDevice = audio.communicationDevice
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val rtc = WebRTCClient(context, object : WebRTCClient.Callbacks {
            override fun onOfferReady(sdp: String) {
                val target = chatId
                if (!active(id) || target <= 9) return
                current.value = current.value?.copy(setupStep = TalkSetupStep.SendingInvite)
                scope.launch {
                    guarded(id) {
                        val placed = place(target, sdp)
                        if (!active(id)) {
                            runCatching { terminate(placed) }
                            return@guarded
                        }
                        messageId = placed
                        current.value = current.value?.copy(setupStep = TalkSetupStep.WaitingForAnswer)
                        armTimeout(id, TALK_RING_MS, "No answer")
                        val early = deferredEvent
                        deferredEvent = null
                        if (early?.id == placed) event(early)
                    }
                }
            }

            override fun onAnswerReady(sdp: String) {
                val target = messageId
                scope.launch { if (active(id) && target > 9) guarded(id) { answer(target, sdp) } }
            }

            override fun onRemoteVideoTrack(track: VideoTrack) {
                if (active(id)) current.value = current.value?.copy(remoteVideo = track)
            }
            override fun onRemoteAudioTrack(track: AudioTrack) = Unit
            override fun onRemoteMutedStateChanged(audioEnabled: Boolean, videoEnabled: Boolean) {
                if (active(id)) current.value = current.value?.copy(remoteCamera = videoEnabled)
            }
            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                if (!active(id)) return
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        timer?.cancel()
                        val call = current.value ?: return
                        current.value = call.copy(phase = TalkPhase.Active,
                            connectedAt = call.connectedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime())
                        client?.sendMutedState(!call.muted, call.camera)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> finish("Couldn’t connect")
                    else -> Unit
                }
            }
            override fun onRelayUsageChanged(isRelayUsed: Boolean?) = Unit
            override fun onError(error: String) {
                if (active(id)) finish("Couldn’t connect")
            }
        })
        client = rtc
        rtc.configure(servers)
        val manager = MediaStreamManager(context, rtc.peerConnectionFactory)
        media = manager
        manager.createMediaStream(object : MediaStreamManager.Callback {
            override fun onMediaStreamReady(stream: MediaStream) {
                if (!active(id)) return
                rtc.setLocalMediaStream(stream)
                rtc.setVideoEnabled(false)
                current.value = current.value?.copy(localVideo = rtc.localVideoTrack)
                if (offer == null) rtc.startOutgoingCall() else rtc.acceptIncomingCall(offer)
            }
            override fun onError(error: String) {
                if (active(id)) finish("Couldn’t open call media")
            }
        })
    }

    fun toggleCamera() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Active && !it.switchingCamera } ?: return
        setCameraEnabled(call, !call.camera)
    }

    private fun setCameraEnabled(call: TalkCall, enabled: Boolean) {
        val manager = media ?: return
        val rtc = client ?: return
        if (enabled) {
            if (!manager.startVideoCapture()) return
            rtc.setVideoEnabled(true)
        } else {
            rtc.setVideoEnabled(false)
            manager.stopVideoCapture()
        }
        current.value = call.copy(camera = enabled, frontCamera = manager.isFrontCamera, switchingCamera = false)
        rtc.sendMutedState(!call.muted, enabled)
        videoStats?.cancel()
        videoStats = if (enabled) sampleOutgoingVideo(call.id, rtc) else null
    }

    fun switchCamera() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Active && it.camera && !it.switchingCamera } ?: return
        val manager = media ?: return
        current.value = call.copy(switchingCamera = true)
        manager.switchCamera(object : MediaStreamManager.CameraSwitchCallback {
            override fun onCameraSwitch(isFrontCamera: Boolean) {
                scope.launch {
                    val currentCall = current.value?.takeIf { it.id == call.id && it.camera && it.switchingCamera }
                        ?: return@launch
                    current.value = currentCall.copy(frontCamera = isFrontCamera, switchingCamera = false)
                    videoStats?.cancel()
                    videoStats = client?.let { sampleOutgoingVideo(call.id, it) }
                }
            }

            override fun onError(error: String) {
                scope.launch {
                    val currentCall = current.value?.takeIf { it.id == call.id && it.switchingCamera } ?: return@launch
                    current.value = currentCall.copy(switchingCamera = false)
                    AppLog.record(context, "Talk: couldn’t switch cameras")
                }
            }
        })
    }

    private fun sampleOutgoingVideo(id: String, rtc: WebRTCClient): Job = scope.launch {
        repeat(2) {
            delay(if (it == 0) 3_000 else 6_000)
            if (current.value?.let { call -> call.id == id && call.camera } != true) return@launch
            rtc.getCallStats { report ->
                val stats = report.statsMap
                val outbound = stats.values
                    .filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
                    .maxByOrNull { (it.members["framesEncoded"] as? Number)?.toLong() ?: 0L }
                    ?.members ?: return@getCallStats
                val width = (outbound["frameWidth"] as? Number)?.toInt() ?: return@getCallStats
                val height = (outbound["frameHeight"] as? Number)?.toInt() ?: return@getCallStats
                val fps = (outbound["framesPerSecond"] as? Number)?.toInt()?.let { " at $it fps" }.orEmpty()
                val codec = (outbound["codecId"] as? String)
                    ?.let { stats[it]?.members?.get("mimeType") as? String }
                    ?.takeIf { it in setOf("video/VP8", "video/VP9", "video/H264", "video/AV1") }
                val limit = (outbound["qualityLimitationReason"] as? String)
                    ?.takeIf { it in setOf("none", "cpu", "bandwidth", "other") }
                scope.launch(Dispatchers.IO) {
                    if (current.value?.let { call -> call.id == id && call.camera } == true) {
                        val details = listOfNotNull(codec, limit?.let { "limited by $it" }).joinToString(", ")
                        AppLog.record(context, "Talk: outgoing video ${width}x$height$fps" +
                            if (details.isBlank()) "" else " ($details)")
                    }
                }
            }
        }
    }

    fun toggleMute() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Active } ?: return
        client?.setAudioEnabled(call.muted)
        current.value = call.copy(muted = !call.muted)
        client?.sendMutedState(call.muted, call.camera)
    }

    fun toggleSpeaker() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Active } ?: return
        val devices = audio.availableCommunicationDevices
        val device = if (call.speaker) devices.firstOrNull {
            it.id == previousDevice?.id && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        else devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (device != null && audio.setCommunicationDevice(device)) current.value = call.copy(speaker = !call.speaker)
        else if (call.speaker && device == null) {
            audio.clearCommunicationDevice()
            current.value = call.copy(speaker = false)
        }
    }

    fun ring() {
        val call = current.value?.takeIf { it.phase == TalkPhase.Incoming } ?: return
        clearAlert(call.id)
        if (ringtone == null) {
            ringtone = runCatching { RingtoneManager.getRingtone(context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply { isLooping = true; play() } }.getOrNull()
        }
    }

    fun silence() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    fun onAppHidden() {
        current.value?.takeIf { it.camera }?.let { setCameraEnabled(it, false) }
        silence()
    }

    fun decline(id: String) { if (current.value?.id == id) finish("Call declined") }

    fun end() {
        if (current.value?.phase == TalkPhase.Finished) current.value = null
        else finish("Call ended")
    }

    private fun busy() = current.value?.phase?.let { it != TalkPhase.Finished } == true
    private fun active(id: String) = current.value?.let { it.id == id && it.phase != TalkPhase.Finished } == true

    private fun armTimeout(id: String, time: Long, reason: String) {
        timer?.cancel()
        timer = scope.launch {
            delay(time)
            if (active(id)) finish(reason)
        }
    }

    private suspend fun guarded(id: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.record(context, "Talk: Chatmail call failed", failure)
            if (active(id)) finish(failure.message ?: "Couldn’t connect")
        } catch (_: LinkageError) {
            if (active(id)) finish("Call media is unavailable on this device")
        }
    }

    private fun finish(detail: String, sendEnd: Boolean = true) {
        val call = current.value?.takeIf { it.phase != TalkPhase.Finished } ?: return
        val message = messageId
        messageId = 0
        chatId = 0
        incomingOffer = ""
        deferredEvent = null
        current.value = call.copy(phase = TalkPhase.Finished, detail = detail, camera = false,
            localVideo = null, remoteVideo = null, remoteCamera = false)
        timer?.cancel()
        videoStats?.cancel()
        videoStats = null
        silence()
        clearAlert(call.id)
        runCatching { media?.dispose() }
        runCatching { client?.dispose() }
        media = null
        client = null
        previousMode?.let {
            audio.clearCommunicationDevice()
            previousDevice?.let { device ->
                audio.availableCommunicationDevices.firstOrNull { current -> current.id == device.id }
                    ?.let(audio::setCommunicationDevice)
            }
            audio.mode = it
            audio.abandonAudioFocusRequest(focus)
        }
        previousMode = null
        previousDevice = null
        TalkCallService.stop(context)
        scope.launch {
            if (sendEnd && message > 9) runCatching { terminate(message) }
            completed(call.copy(detail = detail))
        }
    }
}
