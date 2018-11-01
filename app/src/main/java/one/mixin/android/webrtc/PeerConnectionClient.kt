package one.mixin.android.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.StatsReport
import org.webrtc.SurfaceTextureHelper
import timber.log.Timber
import java.util.concurrent.Executors

class PeerConnectionClient(private val context: Context, private val events: PeerConnectionEvents) {
    private val executor = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var isError = false

    init {
        executor.execute {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
            )
        }
    }

    private val pcObserver = PCObserver()
    private val sdpObserverImp = SdpObserverImp()
    private val iceServers = arrayListOf<PeerConnection.IceServer>().apply {
        add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
    }
    var isInitiator = false
    private val sdpConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteCandidateCache = arrayListOf<IceCandidate>()
    private var remoteSdpCache: SessionDescription? = null
    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    fun createPeerConnectionFactory(options: PeerConnectionFactory.Options) {
        if (factory != null) {
            throw IllegalStateException("PeerConnectionFactory has already been constructed")
        }
        executor.execute { createPeerConnectionFactoryInternal(options) }
    }

    fun createOffer(iceServerList: List<PeerConnection.IceServer>) {
        iceServers.addAll(iceServerList)
        executor.execute {
            try {
                val peerConnection = createPeerConnectionInternal()
                isInitiator = true
                sdpConstraint.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                peerConnection.createOffer(sdpObserverImp, sdpConstraint)
            } catch (e: Exception) {
                reportError("Failed to create offer: ${e.message}")
            }
        }
    }

    fun createAnswer(iceServerList: List<PeerConnection.IceServer>) {
        iceServers.addAll(iceServerList)
        executor.execute {
            try {
                val peerConnection = createPeerConnectionInternal()
                isInitiator = false
                sdpConstraint.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                peerConnection.createAnswer(sdpObserverImp, sdpConstraint)
            } catch (e: Exception) {
                reportError("Failed to create answer: ${e.message}")
            }
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (peerConnection != null && peerConnection!!.remoteDescription != null) {
                Log.d("@@@", "peerConnection!!.addIceCandidate(candidate)")
                peerConnection!!.addIceCandidate(candidate)
            } else {
                Log.d("@@@", "remoteCandidateCache.add(candidate)")
                remoteCandidateCache.add(candidate)
            }
        }
    }

    fun removeRemoteIceCandidate(candidates: Array<IceCandidate>) {
        if (peerConnection == null || isError) return
        executor.execute {
            drainCandidatesAndSdp()
            peerConnection!!.removeIceCandidates(candidates)
        }
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        executor.execute {
            if (peerConnection != null) {
                Timber.d("setRemoteDescription signalingState: ${peerConnection!!.signalingState()}")
                peerConnection!!.setRemoteDescription(sdpObserverImp, sdp)
            } else {
                Timber.d("remoteSdpCache = sdp")
                remoteSdpCache = sdp
            }
        }
    }

    fun setAudioEnable(enable: Boolean) {
        if (peerConnection == null || audioTrack == null || isError) return
        executor.execute {
            Log.d("@@@", "setAudioEnable: $enable")
            audioTrack!!.setEnabled(enable)
        }
    }

    fun enableCommunication() {
        if (peerConnection == null || isError) return
        executor.execute {
            Log.d("@@@", "enableCommunication")
            peerConnection!!.setAudioPlayout(true)
            peerConnection!!.setAudioRecording(true)
        }
    }

    fun close() {
        executor.execute {
            Timber.d("Closing peer connection")
            peerConnection?.dispose()
            peerConnection = null
            Timber.d("Closing audio source")
            audioSource?.dispose()
            audioSource = null
            Timber.d("closing surfaceTextHelper")
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
            Timber.d("Closing peer connection factory")
            factory?.dispose()
            factory = null
            events.onPeerConnectionClosed()
            PeerConnectionFactory.stopInternalTracingCapture()
            PeerConnectionFactory.shutdownInternalTracer()
        }
    }

    private fun reportError(error: String) {
        executor.execute {
            if (!isError) {
                events.onPeerConnectionError(error)
                isError = true
            }
        }
    }

    private fun createPeerConnectionInternal(): PeerConnection {
        if (factory == null || isError) {
            throw IllegalStateException("PeerConnectionFactory is not created")
        }
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
        remoteCandidateCache = arrayListOf()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory!!.createPeerConnection(rtcConfig, pcObserver)
            ?: throw IllegalStateException("PeerConnection is not created")
        peerConnection!!.setAudioPlayout(false)
        peerConnection!!.setAudioRecording(false)

        if (remoteSdpCache != null) {
            Timber.d("createPeerConnectionInternal setRemoteDescription  signalingState: ${peerConnection!!.signalingState()}")
            peerConnection!!.setRemoteDescription(sdpObserverImp, remoteSdpCache)
        }

        peerConnection!!.addTrack(createAudioTrack())
        return peerConnection!!
    }

    private fun createPeerConnectionFactoryInternal(options: PeerConnectionFactory.Options) {
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    private fun drainCandidatesAndSdp() {
        if (peerConnection == null) return

        Log.d("@@@", "drainCandidatesAndSdp remoteCandidateCache size: ${remoteCandidateCache.size}, remoteSdpCache is null: ${remoteSdpCache == null}")
        remoteCandidateCache.forEach {
            peerConnection!!.addIceCandidate(it)
            remoteCandidateCache.clear()
        }
        if (remoteSdpCache != null && peerConnection!!.remoteDescription == null) {
            peerConnection!!.setRemoteDescription(sdpObserverImp, remoteSdpCache)
            remoteSdpCache = null
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val audioConstraints = MediaConstraints().apply {
//            optional.add(MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"))
//            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
//            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
//            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
//            mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))
        }
        audioSource = factory!!.createAudioSource(audioConstraints)
        audioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack!!.setEnabled(true)
        return audioTrack!!
    }

    private inner class SdpObserverImp : SdpObserver {
        private var localSdp: SessionDescription? = null

        override fun onCreateSuccess(originSdp: SessionDescription) {
            if (localSdp != null) {
                reportError("Multiple SDP create.")
                return
            }
            val sdp = SessionDescription(originSdp.type, originSdp.description)
            localSdp = sdp
            executor.execute {
                if (peerConnection != null && !isError) {
                    Timber.d("Set local SDP from ${sdp.type}")
                    peerConnection!!.setLocalDescription(sdpObserverImp, sdp)
                }
            }
        }

        override fun onSetSuccess() {
            if (localSdp == null || peerConnection == null || isError) return

            executor.execute {
                if (isInitiator) {
                    if (peerConnection!!.remoteDescription == null) {
                        Timber.d("Local SDP set successfully")
                        events.onLocalDescription(localSdp!!)
                    } else {
                        Timber.d("Remote SDP set successfully")
                        drainCandidatesAndSdp()
                    }
                } else {
                    if (peerConnection!!.localDescription != null) {
                        Timber.d("Local SDP set successfully")
                        events.onLocalDescription(localSdp!!)
                        drainCandidatesAndSdp()
                    } else {
                        Timber.d("Remote SDP set successfully")
                    }
                }
            }
        }

        override fun onSetFailure(error: String?) {
            Timber.d("onSetFailure error: $error")
        }

        override fun onCreateFailure(error: String?) {
            reportError("createSDP error: $error")
        }
    }

    private inner class PCObserver : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            executor.execute { events.onIceCandidate(candidate) }
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            Timber.d("onIceConnectionChange: $newState")
            executor.execute {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED -> events.onIceConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED -> events.onIceDisconnected()
                    PeerConnection.IceConnectionState.FAILED -> reportError("ICE connection failed")
                    else -> {
                    }
                }
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Timber.d("onIceGatheringChange: $newState")
        }

        override fun onAddStream(stream: MediaStream) {
            Timber.d("onAddStream")
            stream.audioTracks.forEach { it.setEnabled(true) }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {
            Timber.d("SignalingState: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
            Timber.d("onIceCandidatesRemoved")
            executor.execute { events.onIceCandidatesRemoved(candidates) }
        }

        override fun onRemoveStream(stream: MediaStream) {
            stream.videoTracks[0].dispose()
        }

        override fun onRenegotiationNeeded() {
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        }
    }

    /**
     * Peer connection events.
     */
    interface PeerConnectionEvents {
        /**
         * Callback fired once local SDP is created and set.
         */
        fun onLocalDescription(sdp: SessionDescription)

        /**
         * Callback fired once local Ice candidate is generated.
         */
        fun onIceCandidate(candidate: IceCandidate)

        /**
         * Callback fired once local ICE candidates are removed.
         */
        fun onIceCandidatesRemoved(candidates: Array<IceCandidate>)

        /**
         * Callback fired once connection is established (IceConnectionState is
         * CONNECTED).
         */
        fun onIceConnected()

        /**
         * Callback fired once connection is closed (IceConnectionState is
         * DISCONNECTED).
         */
        fun onIceDisconnected()

        /**
         * Callback fired once peer connection is closed.
         */
        fun onPeerConnectionClosed()

        /**
         * Callback fired once peer connection statistics is ready.
         */
        fun onPeerConnectionStatsReady(reports: Array<StatsReport>)

        /**
         * Callback fired once peer connection error happened.
         */
        fun onPeerConnectionError(description: String)
    }

    companion object {
        const val TAG = "PeerConnectionClient"

        private const val DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement"
        private const val STREAM_ID = "ARDAMS"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val AUDIO_TRACK_ID = "ARDAMSa0"

        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"
    }
}