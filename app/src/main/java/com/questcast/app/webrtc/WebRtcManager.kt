package com.questcast.app.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.questcast.app.util.AppLogger as Log
import com.questcast.app.model.CastConfig
import org.webrtc.*
import java.util.concurrent.Executors

class WebRtcManager(
    private val context: Context,
    private val config: CastConfig = CastConfig(),
    private val listener: Listener
) {
    companion object {
        private const val TAG = "QuestCast"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val STREAM_ID = "ARDAMS"
    }

    interface Listener {
        fun onLocalDescriptionCreated(sdp: String)
        fun onIceCandidateGenerated(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onIceConnectionChange(newState: PeerConnection.IceConnectionState)
        fun onConnectionChange(newState: PeerConnection.PeerConnectionState)
        fun onError(description: String)
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var isCapturing = false

    fun initialize() {
        Log.i(TAG, "QuestCast: initializing WebRTC infrastructure")
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        eglBase = EglBase.create()
        val eglBaseContext = eglBase!!.eglBaseContext

        val videoEncoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext,
            /* enableIntelVp8 = */ true,
            /* enableH264HighProfile = */ true
        )
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun startScreenCapture(permissionResultData: Intent) {
        if (isCapturing) return
        val factory = peerConnectionFactory ?: run {
            Log.e(TAG, "QuestCast: PeerConnectionFactory is null during startScreenCapture")
            return
        }

        try {
            Log.i(TAG, "QuestCast: starting screen capture ${config.width}x${config.height} @ ${config.fps}fps")
            val eglBaseContext = eglBase!!.eglBaseContext

            val capturer = ScreenCapturerAndroid(permissionResultData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "QuestCast: MediaProjection stopped by system")
                    stopCapture()
                }
            })
            screenCapturer = capturer

            surfaceTextureHelper = SurfaceTextureHelper.create("QuestCastScreenCaptureThread", eglBaseContext)
            videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            capturer.startCapture(config.width, config.height, config.fps)

            videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).apply {
                setEnabled(true)
            }

            isCapturing = true
            Log.i(TAG, "QuestCast: video pipeline started")
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error starting screen capture pipeline", e)
            listener.onError("Failed to start screen capture: ${e.message}")
        }
    }

    fun createPeerConnection() {
        val factory = peerConnectionFactory ?: return
        closePeerConnection()

        Log.i(TAG, "QuestCast: creating RTCPeerConnection (LAN mode, no external STUN/TURN)")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    Log.i(TAG, "QuestCast: ICE candidate gathered: ${candidate.sdpMid} -> ${candidate.sdp.take(40)}")
                    listener.onIceCandidateGenerated(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                if (newState != null) {
                    Log.i(TAG, "QuestCast: ICE state = $newState")
                    listener.onIceConnectionChange(newState)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null) {
                    Log.i(TAG, "QuestCast: connection state = $newState")
                    listener.onConnectionChange(newState)
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "QuestCast: ICE gathering state = $newState")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        // Add local video track to peer connection with optimized low-latency settings
        val track = videoTrack
        if (track != null && peerConnection != null) {
            val rtpSender = peerConnection?.addTrack(track, listOf(STREAM_ID))
            Log.i(TAG, "QuestCast: added video track to peer connection")

            try {
                val params = rtpSender?.parameters
                if (params != null) {
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    for (encoding in params.encodings) {
                        encoding.minBitrateBps = config.minBitrateKbps * 1000
                        encoding.maxBitrateBps = config.maxBitrateKbps * 1000
                        encoding.maxFramerate = config.fps
                    }
                    rtpSender.parameters = params
                    Log.i(TAG, "QuestCast: RtpSender configured with MAINTAIN_FRAMERATE & ${config.fps} FPS")
                }
            } catch (e: Exception) {
                Log.w(TAG, "QuestCast: unable to set RtpSender parameters: ${e.message}")
            }
        }
    }

    fun createAndSendOffer() {
        val pc = peerConnection ?: run {
            createPeerConnection()
            peerConnection ?: return
        }

        val sdpMediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        Log.i(TAG, "QuestCast: creating offer")
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                val tunedSdp = preferCodec(desc.description, "H264")
                val modifiedDesc = SessionDescription(desc.type, tunedSdp)

                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.i(TAG, "QuestCast: setLocalDescription success, dispatching offer")
                        listener.onLocalDescriptionCreated(modifiedDesc.description)
                    }
                    override fun onCreateFailure(err: String?) {}
                    override fun onSetFailure(err: String?) {
                        Log.e(TAG, "QuestCast: setLocalDescription failure: $err")
                    }
                }, modifiedDesc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "QuestCast: createOffer failure: $err")
                listener.onError("Failed to create offer: $err")
            }
            override fun onSetFailure(err: String?) {}
        }, sdpMediaConstraints)
    }

    fun handleRemoteAnswer(sdp: String) {
        val pc = peerConnection ?: return
        Log.i(TAG, "QuestCast: received answer, setting remote description")
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i(TAG, "QuestCast: setRemoteDescription success")
            }
            override fun onCreateFailure(err: String?) {}
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "QuestCast: setRemoteDescription failure: $err")
            }
        }, desc)
    }

    fun addRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val pc = peerConnection ?: return
        val iceCandidate = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        pc.addIceCandidate(iceCandidate)
    }

    private fun closePeerConnection() {
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error closing PeerConnection", e)
        }
        peerConnection = null
    }

    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        Log.i(TAG, "QuestCast: stopping video pipeline")

        executor.execute {
            try {
                screenCapturer?.stopCapture()
                screenCapturer?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "QuestCast: error disposing screen capturer", e)
            }
            screenCapturer = null

            try {
                surfaceTextureHelper?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "QuestCast: error disposing SurfaceTextureHelper", e)
            }
            surfaceTextureHelper = null

            try {
                videoTrack?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "QuestCast: error disposing VideoTrack", e)
            }
            videoTrack = null

            try {
                videoSource?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "QuestCast: error disposing VideoSource", e)
            }
            videoSource = null

            closePeerConnection()
        }
    }

    fun release() {
        stopCapture()
        executor.execute {
            try {
                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
                eglBase?.release()
                eglBase = null
            } catch (e: Exception) {
                Log.e(TAG, "QuestCast: error releasing WebRTC factory", e)
            }
        }
    }

    /**
     * Reorders codecs in SDP so that the preferred codec (e.g. H264) appears first in the m=video line.
     */
    private fun preferCodec(sdp: String, codec: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (mLineIndex == -1) return sdp

        val rtpMapLines = lines.filter { it.startsWith("a=rtpmap:") && it.contains(codec, ignoreCase = true) }
        if (rtpMapLines.isEmpty()) return sdp

        val preferredPayloadTypes = rtpMapLines.mapNotNull {
            val parts = it.substringAfter("a=rtpmap:").split(" ")
            parts.firstOrNull()
        }
        if (preferredPayloadTypes.isEmpty()) return sdp

        val mLine = lines[mLineIndex]
        val mParts = mLine.split(" ").toMutableList()
        if (mParts.size < 4) return sdp

        val header = mParts.subList(0, 3)
        val payloadTypes = mParts.subList(3, mParts.size).toMutableList()

        // Move preferred payload types to front
        payloadTypes.removeAll(preferredPayloadTypes)
        payloadTypes.addAll(0, preferredPayloadTypes)

        lines[mLineIndex] = (header + payloadTypes).joinToString(" ")
        return lines.joinToString("\r\n")
    }
}
