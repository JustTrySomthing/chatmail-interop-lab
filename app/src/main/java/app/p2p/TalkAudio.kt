package app.p2p

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

internal class TalkAudio(
    private val context: Context,
    ice: String,
    private val stateChanged: (PeerConnection.PeerConnectionState) -> Unit,
    private val focusLost: () -> Unit,
    private val audioFailed: () -> Unit
) : AutoCloseable, PeerConnection.Observer {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val previousMode = audio.mode
    private val previousDevice = audio.communicationDevice
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS || it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) focusLost() }
        .build()
    private val gathered = CompletableDeferred<Unit>()
    private var deviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var focused = false

    init {
        try {
            AppLog.record(context, "Talk audio: initializing WebRTC")
            initialize(context)
            val device = JavaAudioDeviceModule.builder(context)
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(error: String) = reportAudioFailure("microphone initialization")
                    override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) = reportAudioFailure("microphone start ($code)")
                    override fun onWebRtcAudioRecordError(error: String) = reportAudioFailure("microphone")
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(error: String) = reportAudioFailure("playback initialization")
                    override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, error: String) = reportAudioFailure("playback start ($code)")
                    override fun onWebRtcAudioTrackError(error: String) = reportAudioFailure("playback")
                }).createAudioDeviceModule().also { deviceModule = it }
            val engine = PeerConnectionFactory.builder().setAudioDeviceModule(device)
                .createPeerConnectionFactory().also { factory = it }
            val servers = parseIceServers(ice)
            AppLog.record(context, "Talk audio: ICE servers ${servers.flatMap { it.urls }.groupingBy { it.substringBefore(':') }.eachCount()}")
            val config = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val pc = checkNotNull(engine.createPeerConnection(config, this)).also { connection = it }
            pc.setAudioRecording(false)
            pc.setAudioPlayout(false)
            val input = engine.createAudioSource(MediaConstraints()).also { source = it }
            val microphone = engine.createAudioTrack("talk", input).also { track = it }
            microphone.setEnabled(false)
            checkNotNull(pc.addTrack(microphone, listOf("talk")))
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun reportAudioFailure(stage: String) {
        AppLog.record(context, "Talk audio: WebRTC $stage failed")
        audioFailed()
    }

    suspend fun prepare(offer: String?): String {
        val pc = checkNotNull(connection)
        if (offer != null) applyRemote(SessionDescription.Type.OFFER, offer)
        val observer = DescriptionObserver()
        if (offer == null) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
        val description = observer.created.await()
        val setter = DescriptionObserver()
        pc.setLocalDescription(setter, description)
        setter.applied.await()
        val complete = withTimeoutOrNull(12_000) { gathered.await() } != null
        val sdp = checkNotNull(pc.localDescription).description
        AppLog.record(context, "Talk audio: local ${if (offer == null) "offer" else "answer"} ICE ${if (complete) "complete" else "timed out"}, ${talkCandidateCounts(sdp)}")
        localTalkSdpError(sdp, answer = offer != null)?.let { error(it) }
        AppLog.record(context, "Talk audio: local ${if (offer == null) "offer" else "answer"} prepared")
        return sdp
    }

    suspend fun applyRemote(type: SessionDescription.Type, sdp: String) {
        require(validTalkSdp(sdp)) { "Invalid remote ${type.canonicalForm()}" }
        AppLog.record(context, "Talk audio: applying remote ${type.canonicalForm()}, ${talkCandidateCounts(sdp)}")
        val observer = DescriptionObserver()
        checkNotNull(connection).setRemoteDescription(observer, SessionDescription(type, sdp))
        observer.applied.await()
        AppLog.record(context, "Talk audio: remote ${type.canonicalForm()} applied")
    }

    fun activate() {
        if (focused) return
        val result = audio.requestAudioFocus(focus)
        AppLog.record(context, "Talk audio: audio focus result $result")
        check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable" }
        focused = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        connection?.setAudioRecording(true)
        connection?.setAudioPlayout(true)
        track?.setEnabled(true)
    }

    fun muteMicrophone(muted: Boolean) { track?.setEnabled(!muted); deviceModule?.setMicrophoneMute(muted) }

    fun speaker(enabled: Boolean): Boolean {
        val devices = audio.availableCommunicationDevices
        val device = if (enabled) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else devices.firstOrNull { it.id == previousDevice?.id && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        return if (device != null) audio.setCommunicationDevice(device) else if (!enabled) {
            audio.clearCommunicationDevice()
            true
        } else false
    }

    override fun close() {
        connection?.setAudioRecording(false)
        connection?.setAudioPlayout(false)
        track?.setEnabled(false)
        connection?.dispose()
        connection = null
        track?.dispose()
        track = null
        source?.dispose()
        source = null
        factory?.dispose()
        factory = null
        deviceModule?.release()
        deviceModule = null
        if (focused) {
            audio.clearCommunicationDevice()
            previousDevice?.let { saved ->
                audio.availableCommunicationDevices.firstOrNull { it.id == saved.id }
                    ?.let(audio::setCommunicationDevice)
            }
            audio.mode = previousMode
            audio.abandonAudioFocusRequest(focus)
            focused = false
        }
    }

    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
        AppLog.record(context, "Talk: WebRTC connection $state")
        stateChanged(state)
    }
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
    }
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        if (state != PeerConnection.IceConnectionState.CLOSED) AppLog.record(context, "Talk audio: ICE state $state")
    }
    override fun onIceCandidateError(event: IceCandidateErrorEvent) {
        val scheme = event.url.substringBefore(':')
        if (scheme in setOf("stun", "stuns", "turn", "turns")) AppLog.record(context, "Talk audio: $scheme candidate error ${event.errorCode}")
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = channel.dispose()
    override fun onRenegotiationNeeded() = Unit

    private class DescriptionObserver : SdpObserver {
        val created = CompletableDeferred<SessionDescription>()
        val applied = CompletableDeferred<Unit>()
        override fun onCreateSuccess(description: SessionDescription) { created.complete(description) }
        override fun onSetSuccess() { applied.complete(Unit) }
        override fun onCreateFailure(error: String) { created.completeExceptionally(IllegalStateException("Couldn’t prepare call audio")) }
        override fun onSetFailure(error: String) { applied.completeExceptionally(IllegalStateException("Couldn’t connect call audio")) }
    }

    private companion object {
        private var initialized = false
        @Synchronized fun initialize(context: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialized = true
            }
        }
    }
}

private fun parseIceServers(raw: String): List<PeerConnection.IceServer> {
    val entries = JSONArray(raw)
    return (0 until entries.length()).mapNotNull { index ->
        val entry = entries.optJSONObject(index) ?: return@mapNotNull null
        val urls = when (val value = entry.opt("urls")) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }
            is String -> listOf(value)
            else -> emptyList()
        }.flatMap { if (it.startsWith("turn:") && !it.contains("?")) listOf(it, "$it?transport=tcp") else listOf(it) }.distinct().filter { it.substringBefore(':') in setOf("stun", "stuns", "turn", "turns") }
        if (urls.isEmpty()) null else PeerConnection.IceServer.builder(urls)
            .setUsername(entry.optString("username")).setPassword(entry.optString("credential")).createIceServer()
    }
}

internal fun validTalkSdp(sdp: String): Boolean {
    if (sdp.length !in 1..10_000 || !sdp.startsWith("v=0")) return false
    val media = sdp.lineSequence().filter { it.startsWith("m=") }.toList()
    return media.size == 1 && media.single().startsWith("m=audio ") &&
        media.single().split(' ').getOrNull(2) == "UDP/TLS/RTP/SAVPF" &&
        sdp.lineSequence().any { fingerprint.matches(it) }
}

private val fingerprint = Regex("a=fingerprint:sha-256 ([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")

internal fun talkCandidateCounts(sdp: String): Map<String, Int> =
    sdp.lineSequence().filter { it.startsWith("a=candidate:") }
        .mapNotNull { Regex("\\btyp (host|srflx|prflx|relay)(?:\\s|$)").find(it)?.groupValues?.get(1) }
        .groupingBy { it }.eachCount()

internal fun localTalkSdpError(sdp: String, answer: Boolean): String? {
    if (!validTalkSdp(sdp)) return "Invalid local WebRTC ${if (answer) "answer" else "offer"}"
    if (sdp.lineSequence().any { it.startsWith("a=candidate:") }) return null
    if (answer && sdp.lineSequence().any { it.startsWith("a=ice-ufrag:") && it.length > 12 } &&
        sdp.lineSequence().any { it.startsWith("a=ice-pwd:") && it.length > 10 }) return null
    return "No ICE candidates for the ${if (answer) "incoming" else "outgoing"} call"
}
