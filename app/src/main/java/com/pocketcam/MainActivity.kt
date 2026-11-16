package com.pocketcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*

import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val workerUrl = "https://icon.chenneww6666.workers.dev"
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var offerStarted = false
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private lateinit var passwordInput: EditText
    private lateinit var info: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

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

        passwordInput = EditText(this).apply {
            setSingleLine(true)
            hint = "輸入單一連線密碼"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9EABC4.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passwordInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })

        info = TextView(this).apply {
            text = "輸入密碼後即可啟動攝影機。"
            textSize = 15f
            setTextColor(0xFFB9C5DA.toInt())
        }
        root.addView(info, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 18 })

        startButton = Button(this).apply {
            text = "開始攝影機"
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
            text = "直播期間會顯示 Android 前景服務通知。請只在自己有權拍攝的環境使用。"
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
        if (requestCode == 7 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startCamera()
        } else {
            info.text = "需要相機與麥克風權限才能開始直播。"
        }
    }

    private fun startCamera() {
        val base = workerUrl
        val password = passwordInput.text.toString()

        if (password.isBlank()) {
            info.text = "請先輸入連線密碼。"
            return
        }

        startButton.isEnabled = false
        info.text = "正在啟動攝影機…"

        try {
            // Start the foreground service before opening the camera or microphone.
            ContextCompat.startForegroundService(
                this,
                Intent(this, KeepAliveService::class.java)
            )

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            eglBase = EglBase.create()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peer = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    runOnUiThread {
                        if (peer != null) info.text = "影音連線：$state"
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) return
                    send(JSONObject().put("type", "candidate").put(
                        "candidate",
                        JSONObject()
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                            .put("candidate", candidate.sdp)
                    ))
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: RtpReceiver?,
                    streams: Array<out MediaStream>?
                ) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
            }) ?: throw IllegalStateException("無法建立 WebRTC PeerConnection")

            startCapture()

            val wsUrl = base.replaceFirst("https://", "wss://") + "/ws"
            socket = http.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "hello")
                                .put("role", "camera")
                                .put("password", password)
                                .toString()
                        )
                        runOnUiThread {
                            info.text = "已連接訊號伺服器，正在驗證密碼…"
                            stopButton.isEnabled = true
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // OkHttp callbacks run on a worker thread. Keep every PeerConnection
                        // operation on the UI thread to serialize it with camera/lifecycle work.
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

        val videoTrack = f.createVideoTrack("pocketcam-video", source)
        val aSource = f.createAudioSource(MediaConstraints())
        audioSource = aSource
        val audioTrack = f.createAudioTrack("pocketcam-audio", aSource)

        peer?.addTrack(videoTrack, listOf("pocketcam-stream"))
        peer?.addTrack(audioTrack, listOf("pocketcam-stream"))
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "authenticated" -> runOnUiThread {
                info.text = "密碼驗證成功，等待觀看端連線…"
            }

            "peer-ready" -> {
                if (!offerStarted) createOffer()
            }

            "answer" -> {
                val sdp = message.optJSONObject("sdp") ?: return
                val currentPeer = peer ?: return
                currentPeer.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {}
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            val queued = pendingRemoteCandidates.toList()
                            pendingRemoteCandidates.clear()
                            queued.forEach { candidate ->
                                try {
                                    currentPeer.addIceCandidate(candidate)
                                } catch (e: Exception) {
                                    Log.w("PocketCam", "Could not add queued ICE candidate", e)
                                }
                            }
                        }
                        override fun onCreateFailure(error: String?) {
                            runOnUiThread { info.text = "套用遠端連線資料失敗：$error" }
                        }
                        override fun onSetFailure(error: String?) {
                            remoteDescriptionSet = false
                            runOnUiThread { info.text = "套用遠端連線資料失敗：$error" }
                        }
                    },
                    SessionDescription(
                        SessionDescription.Type.ANSWER,
                        sdp.optString("sdp")
                    )
                )
            }

            "candidate" -> {
                val c = message.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    c.optString("sdpMid"),
                    c.optInt("sdpMLineIndex"),
                    c.optString("candidate")
                )
                if (remoteDescriptionSet) {
                    peer?.addIceCandidate(candidate)
                } else {
                    // The viewer can send ICE before its answer reaches this device.
                    pendingRemoteCandidates.add(candidate)
                }
            }

            "error" -> runOnUiThread {
                val error = message.optString("message", "未知錯誤")
                stopCamera(updateInfo = false)
                info.text = "伺服器拒絕連線：$error"
            }

            "peer-left" -> {
                offerStarted = false
                remoteDescriptionSet = false
                pendingRemoteCandidates.clear()
                info.text = "觀看端已離線。攝影機仍在等待連線。"
            }
        }
    }

    private fun createOffer() {
        val p = peer ?: return
        if (offerStarted) return
        offerStarted = true
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()

        try {
            p.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null || p !== peer) {
                        offerStarted = false
                        return
                    }
                    try {
                        p.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(value: SessionDescription?) {}
                            override fun onSetSuccess() {
                                if (p !== peer) return
                                send(
                                    JSONObject().put("type", "offer").put(
                                        "sdp",
                                        JSONObject().put("type", "offer")
                                            .put("sdp", desc.description)
                                    )
                                )
                            }
                            override fun onCreateFailure(error: String?) {
                                offerStarted = false
                                runOnUiThread { info.text = "建立本機連線資料失敗：$error" }
                            }
                            override fun onSetFailure(error: String?) {
                                offerStarted = false
                                runOnUiThread { info.text = "設定本機連線資料失敗：$error" }
                            }
                        }, desc)
                    } catch (e: Exception) {
                        offerStarted = false
                        Log.e("PocketCam", "setLocalDescription failed", e)
                        runOnUiThread { info.text = "設定本機連線資料失敗：${e.message}" }
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    offerStarted = false
                    runOnUiThread { info.text = "建立 Offer 失敗：$error" }
                }
                override fun onSetFailure(error: String?) {
                    offerStarted = false
                }
            }, MediaConstraints())
        } catch (e: Exception) {
            offerStarted = false
            Log.e("PocketCam", "createOffer failed", e)
            info.text = "建立影音連線失敗：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun send(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun stopCamera(updateInfo: Boolean = true) {
        offerStarted = false
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        try { socket?.close(1000, "Camera stopped") } catch (_: Exception) {}
        socket = null

        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        capturer = null

        try { textureHelper?.dispose() } catch (_: Exception) {}
        textureHelper = null

        try { peer?.close() } catch (_: Exception) {}
        try { peer?.dispose() } catch (_: Exception) {}
        peer = null

        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { eglBase?.release() } catch (_: Exception) {}
        eglBase = null

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
