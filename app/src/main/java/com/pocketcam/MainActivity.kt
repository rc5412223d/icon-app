package com.pocketcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import java.util.UUID
import kotlin.random.Random

class MainActivity : Activity() {
    private val http = OkHttpClient()
    private var socket: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var roomId = ""
    private var pin = ""
    private lateinit var serverInput: EditText
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
            text = "PocketCam"; textSize = 28f; setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Android 即時攝影機"; textSize = 14f; setTextColor(0xFF9EABC4.toInt())
        })
        serverInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Worker 網址，例如 https://pocketcam-signal.xxx.workers.dev"
            setText("https://YOUR-WORKER.workers.dev")
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9EABC4.toInt())
        }
        root.addView(serverInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 28 })
        info = TextView(this).apply {
            text = "尚未開始。請先部署 icon-web 的 Worker。"
            textSize = 15f; setTextColor(0xFFB9C5DA.toInt())
        }
        root.addView(info, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 18 })
        startButton = Button(this).apply {
            text = "開始攝影機"
            setOnClickListener { requestAndStart() }
        }
        stopButton = Button(this).apply {
            text = "停止直播"; isEnabled = false
            setOnClickListener { stopCamera() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })
        root.addView(stopButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 })
        root.addView(TextView(this).apply {
            text = "直播期間會顯示 Android 前景服務通知。請只在自己有權拍攝的環境使用。"
            textSize = 12f; setTextColor(0xFF9EABC4.toInt())
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 })
        setContentView(root)
    }

    private fun requestAndStart() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
        else startCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCamera()
        else info.text = "需要相機與麥克風權限才能開始直播。"
    }

    private fun startCamera() {
        val base = serverInput.text.toString().trim().removeSuffix("/")
        if (!base.startsWith("https://") || base.contains("YOUR-WORKER")) {
            info.text = "請先部署 icon-web 的 Worker，並填入 https:// 開頭的 Worker 網址。"
            return
        }
        roomId = UUID.randomUUID().toString()
        pin = Random.nextInt(100000, 1000000).toString()
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            peer = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    runOnUiThread { info.text = "房間 ID：$roomId\n配對碼：$pin\n影音連線：$state" }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null) return
                    send(JSONObject().put("type", "candidate").put("candidate", JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)))
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onTrack(transceiver: RtpTransceiver?) {}
            }) ?: throw IllegalStateException("無法建立 WebRTC PeerConnection")

            startCapture()
            val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/$roomId"
            socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "hello").put("role", "camera")
                        .put("pin", pin).toString())
                    runOnUiThread {
                        info.text = "房間 ID：$roomId\n配對碼：$pin\n等待網頁觀看端連線…"
                        startButton.isEnabled = false
                        stopButton.isEnabled = true
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSignal(JSONObject(text))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread { info.text = "訊號連線失敗：${t.message}" }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runOnUiThread { info.text = "訊號連線已關閉" }
                }
            })
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            info.text = "啟動失敗：${e.message}"
            stopCamera()
        }
    }

    private fun startCapture() {
        val f = factory ?: return
        val source = f.createVideoSource(false)
        videoSource = source
        val enumerator = Camera2Enumerator(this)
        val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("找不到可用鏡頭")
        capturer = enumerator.createCapturer(cameraName, null)
        textureHelper = SurfaceTextureHelper.create("PocketCamCapture", f.eglBaseContext)
        capturer?.initialize(textureHelper, this, source.capturerObserver)
        capturer?.startCapture(1280, 720, 24)
        val videoTrack = f.createVideoTrack("pocketcam-video", source)
        audioSource = f.createAudioSource(MediaConstraints())
        val audioTrack = f.createAudioTrack("pocketcam-audio", audioSource)
        peer?.addTrack(videoTrack, listOf("pocketcam-stream"))
        peer?.addTrack(audioTrack, listOf("pocketcam-stream"))
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "authenticated" -> runOnUiThread {
                info.text = "房間 ID：$roomId\n配對碼：$pin\n訊號伺服器已驗證，等待觀看端…"
            }
            "peer-ready" -> createOffer()
            "answer" -> {
                val sdp = message.optJSONObject("sdp") ?: return
                peer?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp.optString("sdp")))
            }
            "candidate" -> {
                val c = message.optJSONObject("candidate") ?: return
                peer?.addIceCandidate(IceCandidate(
                    c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")
                ))
            }
            "error" -> runOnUiThread { info.text = "伺服器錯誤：${message.optString("message")}" }
            "peer-left" -> runOnUiThread {
                info.text = "觀看端已離線\n房間 ID：$roomId\n配對碼：$pin"
            }
        }
    }

    private fun createOffer() {
        val p = peer ?: return
        p.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                p.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription?) {}
                    override fun onSetSuccess() {
                        send(JSONObject().put("type", "offer").put("sdp", JSONObject()
                            .put("type", "offer").put("sdp", desc.description)))
                    }
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                runOnUiThread { info.text = "建立 Offer 失敗：$error" }
            }
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())
    }

    private fun send(json: JSONObject) { socket?.send(json.toString()) }

    private fun stopCamera() {
        try { socket?.close(1000, "Camera stopped") } catch (_: Exception) {}
        socket = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        textureHelper?.dispose(); textureHelper = null
        peer?.close(); peer?.dispose(); peer = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        factory?.dispose(); factory = null
        stopService(Intent(this, KeepAliveService::class.java))
        if (::startButton.isInitialized) startButton.isEnabled = true
        if (::stopButton.isInitialized) stopButton.isEnabled = false
        if (::info.isInitialized) info.text = "已停止直播。"
    }

    override fun onDestroy() {
        stopCamera()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
