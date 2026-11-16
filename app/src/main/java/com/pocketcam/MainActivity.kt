package com.pocketcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val workerUrl = "https://icon.chenneww6666.workers.dev"
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var cameraClientId = ""
    private val viewerSessions = linkedMapOf<String, ViewerSession>()

    private lateinit var info: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private class ViewerSession(val peer: PeerConnection) {
        var offerStarted = false
        var remoteDescriptionSet = false
        val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF101522.toInt())
        }

        root.addView(TextView(this).apply {
            text = "PocketCam"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Android 即時攝影機"
            textSize = 14f
            setTextColor(0xFF9EABC4.toInt())
        })

        info = TextView(this).apply {
            text = "按下開始直播即可啟動攝影機，不需要輸入密碼。"
            textSize = 15f
            setTextColor(0xFFB9C5DA.toInt())
        }
        root.addView(info, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        startButton = Button(this).apply {
            text = "開始直播"
            setOnClickListener { requestAndStart() }
        }
        stopButton = Button(this).apply {
            text = "停止直播"
            isEnabled = false
            setOnClickListener { stopCamera() }
        }

        root.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })
        root.addView(stopButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 })

        root.addView(TextView(this).apply {
            text = "直播期間會顯示 Android 前景服務通知。網頁觀看者必須輸入密碼；最多同時 4 位。請只在自己有權拍攝且已告知相關人員的環境使用。"
            textSize = 12f
            setTextColor(0xFF9EABC4.toInt())
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        setContentView(root)
    }

    private fun requestAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            val cameraGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted && audioGranted) {
                startCamera()
            } else {
                info.text = "需要相機與麥克風權限才能開始直播。"
            }
        }
    }

    private fun createRtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
    }

    private fun startCamera() {
        if (socket != null || factory != null) return

        startButton.isEnabled = false
        info.text = "正在啟動攝影機…"

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, KeepAliveService::class.java)
            )

            cameraClientId = UUID.randomUUID().toString().replace("-", "")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            eglBase = EglBase.create()
            startCapture()

            val wsUrl = workerUrl.replaceFirst("https://", "wss://") + "/ws"
            socket = http.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "hello")
                                .put("role", "camera")
                                .put("clientId", cameraClientId)
                                .toString()
                        )
                        runOnUiThread {
                            info.text = "攝影機已啟動，正在連接訊號伺服器…"
                            stopButton.isEnabled = true
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runOnUiThread {
                            if (socket !== webSocket) return@runOnUiThread
                            try {
                                handleSignal(JSONObject(text))
                            } catch (e: Exception) {
                                Log.e("PocketCam", "Failed to handle signaling message", e)
                                info.text = "處理連線訊號失敗：${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        val detail = buildString {
                            append(t.javaClass.simpleName)
                            append(": ")
                            append(t.message ?: "系統未提供錯誤訊息")
                            if (response != null) {
                                append("\nHTTP ")
                                append(response.code)
                                append(" ")
                                append(response.message)
                            }
                        }
                        runOnUiThread {
                            if (socket === webSocket) {
                                stopCamera(updateInfo = false)
                                info.text = "訊號連線失敗：$detail"
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        runOnUiThread {
                            if (socket === webSocket) {
                                stopCamera(updateInfo = false)
                                info.text = "訊號連線已關閉（$code）：$reason"
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("PocketCam", "Camera startup failed", e)
            stopCamera(updateInfo = false)
            info.text = "啟動失敗：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun startCapture() {
        val f = factory ?: throw IllegalStateException("WebRTC 尚未初始化")
        val source = f.createVideoSource(false)
        videoSource = source

        val enumerator = Camera2Enumerator(this)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("找不到可用鏡頭")

        val camera = enumerator.createCapturer(cameraName, null)
            ?: throw IllegalStateException("無法建立相機擷取器")
        capturer = camera

        val helper = SurfaceTextureHelper.create(
            "PocketCamCapture",
            eglBase?.eglBaseContext ?: throw IllegalStateException("無法初始化 EGL")
        )
        textureHelper = helper
        camera.initialize(helper, this, source.capturerObserver)
        camera.startCapture(1280, 720, 24)

        videoTrack = f.createVideoTrack("pocketcam-video", source).apply {
            setEnabled(true)
        }

        val aSource = f.createAudioSource(MediaConstraints())
        audioSource = aSource
        audioTrack = f.createAudioTrack("pocketcam-audio", aSource).apply {
            setEnabled(true)
        }

        Log.i(
            "PocketCam",
            "Capture started: camera=$cameraName, videoTrack=${videoTrack?.id()}, audioTrack=${audioTrack?.id()}"
        )
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "authenticated" -> {
                info.text = "攝影機已上線，等待觀看者連線…"
            }

            "peer-ready" -> {
                if (message.optString("role") == "viewer") {
                    val viewerId = message.optString("peerId")
                    if (viewerId.isNotBlank()) createViewerPeer(viewerId)
                }
            }

            "answer" -> {
                val viewerId = message.optString("from")
                val session = viewerSessions[viewerId] ?: return
                val sdp = message.optJSONObject("sdp") ?: return

                session.peer.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onSetSuccess() {
                            session.remoteDescriptionSet = true
                            val queued = session.pendingRemoteCandidates.toList()
                            session.pendingRemoteCandidates.clear()
                            queued.forEach { candidate ->
                                try {
                                    session.peer.addIceCandidate(candidate)
                                } catch (e: Exception) {
                                    Log.w("PocketCam", "Could not add queued ICE candidate", e)
                                }
                            }
                            Log.i("PocketCam", "Viewer $viewerId remote answer applied")
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e("PocketCam", "Viewer $viewerId answer creation failed: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            session.remoteDescriptionSet = false
                            Log.e("PocketCam", "Viewer $viewerId answer rejected: $error")
                        }
                    },
                    SessionDescription(
                        SessionDescription.Type.ANSWER,
                        sdp.optString("sdp")
                    )
                )
            }

            "candidate" -> {
                val viewerId = message.optString("from")
                val session = viewerSessions[viewerId] ?: return
                val c = message.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    c.optString("sdpMid"),
                    c.optInt("sdpMLineIndex"),
                    c.optString("candidate")
                )
                if (session.remoteDescriptionSet) {
                    session.peer.addIceCandidate(candidate)
                } else {
                    session.pendingRemoteCandidates.add(candidate)
                }
            }

            "peer-left" -> {
                if (message.optString("role") == "viewer") {
                    val viewerId = message.optString("peerId")
                    removeViewerPeer(viewerId)
                    updateViewerStatus()
                }
            }

            "error" -> {
                val error = message.optString("message", "未知錯誤")
                Log.e("PocketCam", "Signaling error: $error")
                if (viewerSessions.isEmpty()) {
                    info.text = "訊號伺服器：$error"
                } else {
                    info.text = "觀看者連線狀態：$error"
                }
            }
        }
    }

    private fun createViewerPeer(viewerId: String) {
        if (viewerSessions.containsKey(viewerId)) return

        val f = factory ?: return
        val video = videoTrack ?: run {
            Log.e("PocketCam", "Cannot create peer: video track is missing")
            return
        }
        val audio = audioTrack ?: run {
            Log.e("PocketCam", "Cannot create peer: audio track is missing")
            return
        }

        val connection = f.createPeerConnection(
            createRtcConfig(),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d("PocketCam", "Viewer $viewerId signaling=$state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.i("PocketCam", "Viewer $viewerId ICE=$state")
                    runOnUiThread { updateViewerStatus() }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d("PocketCam", "Viewer $viewerId ICE gathering=$state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) return
                    sendToViewer(
                        JSONObject().put("type", "candidate").put(
                            "candidate",
                            JSONObject()
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .put("candidate", candidate.sdp)
                        ),
                        viewerId
                    )
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
            }
        ) ?: run {
            Log.e("PocketCam", "Could not create PeerConnection for viewer $viewerId")
            return
        }

        val session = ViewerSession(connection)
        viewerSessions[viewerId] = session

        try {
            val streamIds = listOf("pocketcam-stream")
            val videoTransceiver = connection.addTransceiver(
                video,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    streamIds
                )
            )
            val audioTransceiver = connection.addTransceiver(
                audio,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                    streamIds
                )
            )
            Log.i(
                "PocketCam",
                "Viewer $viewerId transceivers: video=${videoTransceiver != null}, audio=${audioTransceiver != null}"
            )
            createOffer(viewerId, session)
        } catch (e: Exception) {
            Log.e("PocketCam", "Could not add media transceivers for $viewerId", e)
            removeViewerPeer(viewerId)
        }
        updateViewerStatus()
    }

    private fun createOffer(viewerId: String, session: ViewerSession) {
        if (session.offerStarted) return
        session.offerStarted = true

        try {
            session.peer.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null || viewerSessions[viewerId] !== session) {
                        session.offerStarted = false
                        return
                    }

                    val videoSection = desc.description.contains("m=video")
                    val audioSection = desc.description.contains("m=audio")
                    Log.i(
                        "PocketCam",
                        "Viewer $viewerId offer created: videoSection=$videoSection audioSection=$audioSection"
                    )
                    if (!videoSection) {
                        session.offerStarted = false
                        runOnUiThread { info.text = "錯誤：WebRTC Offer 沒有影像 m-line。" }
                        return
                    }

                    session.peer.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(value: SessionDescription?) {}
                        override fun onSetSuccess() {
                            if (viewerSessions[viewerId] !== session) return
                            val local = session.peer.localDescription ?: desc
                            sendToViewer(
                                JSONObject().put("type", "offer").put(
                                    "sdp",
                                    JSONObject()
                                        .put("type", "offer")
                                        .put("sdp", local.description)
                                ),
                                viewerId
                            )
                            Log.i("PocketCam", "Offer sent to viewer $viewerId")
                        }

                        override fun onCreateFailure(error: String?) {
                            session.offerStarted = false
                            Log.e("PocketCam", "Set local description create failure: $error")
                        }

                        override fun onSetFailure(error: String?) {
                            session.offerStarted = false
                            Log.e("PocketCam", "Set local description failed: $error")
                        }
                    }, desc)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    session.offerStarted = false
                    Log.e("PocketCam", "Create offer failed for $viewerId: $error")
                }
                override fun onSetFailure(error: String?) {
                    session.offerStarted = false
                    Log.e("PocketCam", "Create offer set failure for $viewerId: $error")
                }
            }, MediaConstraints())
        } catch (e: Exception) {
            session.offerStarted = false
            Log.e("PocketCam", "createOffer failed for $viewerId", e)
        }
    }

    private fun sendToViewer(json: JSONObject, viewerId: String) {
        socket?.send(json.put("target", viewerId).toString())
    }

    private fun removeViewerPeer(viewerId: String) {
        val session = viewerSessions.remove(viewerId) ?: return
        try { session.peer.close() } catch (_: Exception) {}
        try { session.peer.dispose() } catch (_: Exception) {}
    }

    private fun updateViewerStatus() {
        val total = viewerSessions.size
        val connected = viewerSessions.values.count {
            it.peer.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED ||
                it.peer.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED
        }
        if (::info.isInitialized && socket != null) {
            info.text = "直播中：觀看者 $total/4，已建立影音連線 $connected 位。"
        }
    }

    private fun stopCamera(updateInfo: Boolean = true) {
        val oldSocket = socket
        socket = null
        try { oldSocket?.close(1000, "Camera stopped") } catch (_: Exception) {}

        viewerSessions.keys.toList().forEach { removeViewerPeer(it) }

        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null

        try { textureHelper?.dispose() } catch (_: Exception) {}
        textureHelper = null

        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { audioTrack?.dispose() } catch (_: Exception) {}
        audioTrack = null

        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null

        cameraClientId = ""
        stopService(Intent(this, KeepAliveService::class.java))

        if (::startButton.isInitialized) startButton.isEnabled = true
        if (::stopButton.isInitialized) stopButton.isEnabled = false
        if (updateInfo && ::info.isInitialized) info.text = "已停止直播。"
    }

    override fun onDestroy() {
        stopCamera()
        super.onDestroy()
    }
}
