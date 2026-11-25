package com.nosmai.quickstart.zegocloud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.nosmai.effect.api.NosmaiPreviewView
import com.nosmai.effect.api.NosmaiSDK
import im.zego.zegoexpress.ZegoExpressEngine
import im.zego.zegoexpress.callback.IZegoCustomVideoCaptureHandler
import im.zego.zegoexpress.callback.IZegoEventHandler
import im.zego.zegoexpress.constants.ZegoPlayerState
import im.zego.zegoexpress.constants.ZegoPublishChannel
import im.zego.zegoexpress.constants.ZegoPublisherState
import im.zego.zegoexpress.constants.ZegoRoomStateChangedReason
import im.zego.zegoexpress.constants.ZegoScenario
import im.zego.zegoexpress.constants.ZegoUpdateType
import im.zego.zegoexpress.constants.ZegoVideoBufferType
import im.zego.zegoexpress.constants.ZegoVideoFrameFormat
import im.zego.zegoexpress.constants.ZegoVideoMirrorMode
import im.zego.zegoexpress.constants.ZegoViewMode
import im.zego.zegoexpress.entity.ZegoCanvas
import im.zego.zegoexpress.entity.ZegoCustomVideoCaptureConfig
import im.zego.zegoexpress.entity.ZegoEngineProfile
import im.zego.zegoexpress.entity.ZegoPlayStreamQuality
import im.zego.zegoexpress.entity.ZegoRoomConfig
import im.zego.zegoexpress.entity.ZegoStream
import im.zego.zegoexpress.entity.ZegoUser
import im.zego.zegoexpress.entity.ZegoVideoConfig
import im.zego.zegoexpress.entity.ZegoVideoFrameParam
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Host:     Camera2 -> Nosmai (effects + preview) -> I420 frames -> ZEGO custom video capture.
 * Audience: plain ZEGO player rendering the host's stream.
 *
 * On Android the app owns the camera: [Camera2Helper] opens it and hands every
 * frame to Nosmai, which renders the preview and returns the processed frame.
 */
class LiveActivity : AppCompatActivity() {
    private val config by lazy { DemoConfig(applicationContext) }


    companion object {
        const val EXTRA_IS_HOST = "isHost"
        private const val PERMISSION_REQUEST = 100
        /** Value of [NosmaiSDK.FrameData.format] for planar I420. */
        private const val NOSMAI_FORMAT_I420 = 1
    }

    private val isHost by lazy { intent.getBooleanExtra(EXTRA_IS_HOST, false) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var videoContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var fpsText: TextView
    private var effectsPanel: EffectsPanel? = null

    private var engine: ZegoExpressEngine? = null
    /** Audience: the host stream currently being played. */
    private var playingStreamId: String? = null
    private var remoteView: TextureView? = null

    // Host: Nosmai preview and the camera feeding it.
    private var previewView: NosmaiPreviewView? = null
    private var camera: Camera2Helper? = null
    private var nosmaiStarted = false
    private var isFrontCamera = true

    /** Host: true while ZEGO wants frames. Read on Nosmai's frame thread. */
    @Volatile private var pushing = false
    private val framesSinceTick = AtomicInteger(0)
    /** Reusable direct buffers for the frames handed to ZEGO; used only on Nosmai's frame thread. */
    private val frameBuffers = arrayOfNulls<ByteBuffer>(3)
    private var frameBufferIndex = 0
    private val frameParam = ZegoVideoFrameParam().apply { format = ZegoVideoFrameFormat.I420 }
    private var encodeWidth = 0
    private var encodeHeight = 0

    private var liveStatus = "Preparing…"
    private var toastToken = 0
    private var stopped = false

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoContainer = findViewById(R.id.videoContainer)
        statusText = findViewById(R.id.statusText)
        fpsText = findViewById(R.id.fpsText)
        buildControls()

        setUpZego()
        if (isHost) {
            if (hasPermissions()) startNosmaiCamera() else requestPermissions()
        } else {
            val view = TextureView(this)
            remoteView = view
            videoContainer.addView(view)
            loginRoom()
        }
    }

    /** Release the camera and pause the preview while in the background. */
    override fun onPause() {
        if (isHost && nosmaiStarted) {
            runCatching { camera?.stopCamera() }
            camera = null
            runCatching { previewView?.onPause() }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (isHost && nosmaiStarted && !stopped) {
            runCatching { previewView?.onResume() }
            if (camera == null) startCamera()
        }
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    // ---- UI ----

    private fun buildControls() {
        val closeButton = findViewById<MaterialButton>(R.id.closeButton)
        closeButton.text = if (isHost) "End Live" else "Leave"
        closeButton.setOnClickListener { finish() }
        if (!isHost) return

        val panelView = findViewById<View>(R.id.effectsPanel)
        panelView.visibility = View.VISIBLE
        effectsPanel = EffectsPanel(panelView) { showToast(it) }

        findViewById<MaterialButton>(R.id.flipButton).apply {
            visibility = View.VISIBLE
            setOnClickListener { flipCamera() }
        }
        findViewById<MaterialButton>(R.id.effectsButton).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                panelView.visibility = if (panelView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setStatus(text: String) {
        main.post {
            liveStatus = text
            statusText.text = text
        }
    }

    /** Briefly shows effect feedback in the status pill, then restores the live status. */
    private fun showToast(text: String) {
        val token = ++toastToken
        statusText.text = text
        main.postDelayed({ if (toastToken == token) statusText.text = liveStatus }, 2500)
    }

    // ---- ZEGO ----

    private fun setUpZego() {
        val profile = ZegoEngineProfile().apply {
            appID = config.appID
            appSign = config.appSign
            scenario = ZegoScenario.BROADCAST
            application = getApplication()
        }
        val engine = ZegoExpressEngine.createEngine(profile, zegoEvents)
        if (isHost) {
            // The app owns the camera, so ZEGO must take frames from us.
            // This has to be configured before logging in to the room.
            val capture = ZegoCustomVideoCaptureConfig().apply { bufferType = ZegoVideoBufferType.RAW_DATA }
            engine.enableCustomVideoCapture(true, capture, ZegoPublishChannel.MAIN)
            engine.setCustomVideoCaptureHandler(captureHandler)
            // Send exactly what Nosmai rendered: no extra mirroring and no cropping.
            engine.setVideoMirrorMode(ZegoVideoMirrorMode.NO_MIRROR)
            engine.setCustomVideoCaptureFillMode(ZegoViewMode.ASPECT_FIT, ZegoPublishChannel.MAIN)
            applyEncodeSize(engine, 720, 1280)
        }
        this.engine = engine
    }

    private fun applyEncodeSize(engine: ZegoExpressEngine, width: Int, height: Int) {
        encodeWidth = width
        encodeHeight = height
        engine.setVideoConfig(ZegoVideoConfig().apply {
            setCaptureResolution(width, height)
            setEncodeResolution(width, height)
            setVideoFPS(30)
            setVideoBitrate(1800)
        })
    }

    private fun loginRoom() {
        val engine = engine ?: return
        // Every participant in a room needs a unique user ID.
        val userId = (if (isHost) "host_" else "viewer_") + UUID.randomUUID().toString().take(8)
        engine.loginRoom(config.roomID, ZegoUser(userId), ZegoRoomConfig())
        setStatus("Joining ${config.roomID}…")
    }

    private val zegoEvents = object : IZegoEventHandler() {
        override fun onRoomStateChanged(roomID: String?, reason: ZegoRoomStateChangedReason?,
                                        errorCode: Int, extendedData: JSONObject?) {
            when (reason) {
                ZegoRoomStateChangedReason.LOGINED ->
                    if (isHost) {
                        // Publishing triggers captureHandler.onStart below.
                        engine?.startPublishingStream(config.streamID)
                    } else if (playingStreamId == null) {
                        setStatus("Connected — waiting for host")
                    }
                ZegoRoomStateChangedReason.LOGIN_FAILED,
                ZegoRoomStateChangedReason.RECONNECT_FAILED,
                ZegoRoomStateChangedReason.KICK_OUT ->
                    setStatus("ZEGO room error $errorCode — check AppID / AppSign")
                ZegoRoomStateChangedReason.RECONNECTING -> setStatus("Reconnecting…")
                else -> Unit
            }
        }

        override fun onPublisherStateUpdate(streamID: String?, state: ZegoPublisherState?,
                                            errorCode: Int, extendedData: JSONObject?) {
            if (errorCode != 0) {
                setStatus("ZEGO publish error $errorCode")
            } else if (state == ZegoPublisherState.PUBLISHING) {
                setStatus("LIVE in ${config.roomID}")
            }
        }

        /** Audience: the host started or stopped publishing. */
        override fun onRoomStreamUpdate(roomID: String?, updateType: ZegoUpdateType?,
                                        streamList: ArrayList<ZegoStream>?, extendedData: JSONObject?) {
            if (isHost) return
            val engine = engine ?: return
            val streams = streamList ?: return
            if (updateType == ZegoUpdateType.ADD) {
                val stream = streams.firstOrNull() ?: return
                val view = remoteView ?: return
                if (playingStreamId != null) return
                engine.startPlayingStream(stream.streamID, ZegoCanvas(view).apply { viewMode = ZegoViewMode.ASPECT_FILL })
                playingStreamId = stream.streamID
                setStatus("Watching host")
            } else if (updateType == ZegoUpdateType.DELETE) {
                val current = playingStreamId ?: return
                if (streams.none { it.streamID == current }) return
                engine.stopPlayingStream(current)
                playingStreamId = null
                fpsText.text = "-- FPS"
                setStatus("Host left the stream")
            }
        }

        /** Audience FPS = frames actually rendered from the host's stream (reported every 3s). */
        override fun onPlayerQualityUpdate(streamID: String?, quality: ZegoPlayStreamQuality?) {
            if (isHost || quality == null) return
            fpsText.text = "${quality.videoRenderFPS.roundToInt()} FPS"
        }

        override fun onPlayerStateUpdate(streamID: String?, state: ZegoPlayerState?,
                                         errorCode: Int, extendedData: JSONObject?) {
            if (errorCode != 0) setStatus("ZEGO play error $errorCode")
        }
    }

    private val captureHandler = object : IZegoCustomVideoCaptureHandler() {
        /** ZEGO is ready to receive our frames. */
        override fun onStart(channel: ZegoPublishChannel?) {
            main.post { startPushingFrames() }
        }

        /** ZEGO no longer needs frames (publishing stopped). */
        override fun onStop(channel: ZegoPublishChannel?) {
            main.post { stopPushingFrames() }
        }
    }

    // ---- Nosmai (host only) ----

    private fun startNosmaiCamera() {
        if (stopped) return
        setStatus("Starting Nosmai…")
        NosmaiSDK.initialize(applicationContext, config.nosmaiLicenseKey)

        // Mount the preview and start the effects pipeline.
        val preview = NosmaiPreviewView(this)
        previewView = preview
        videoContainer.addView(preview)
        preview.initializePipeline()
        NosmaiSDK.startProcessing(preview)

        // startProcessing() does not open a camera: the app does, and feeds Nosmai.
        startCamera()

        nosmaiStarted = true
        effectsPanel?.nosmaiReady()
        loginRoom()
    }

    private fun startCamera() {
        val helper = Camera2Helper(this, isFrontCamera)
        camera = helper
        helper.setInputMode(Camera2Helper.InputMode.YUV)

        NosmaiSDK.setCameraFacing(isFrontCamera)
        previewView?.setCameraOrientation(isFrontCamera, helper.sensorOrientation)

        helper.setFrameCallback { y, u, v, width, height, yStride, uStride, vStride, uPixelStride, vPixelStride ->
            previewView?.processYuvFrame(y, u, v, width, height, yStride, uStride, vStride,
                uPixelStride, vPixelStride, frameRotation(helper))
        }
        helper.setCameraErrorCallback { reason -> setStatus("Camera error: $reason") }
        helper.startCamera()
    }

    /**
     * Rotation Nosmai should apply to the incoming camera frame. The values are the
     * SDK's rotation modes (1 = rotate left, 2 = rotate right), not degrees.
     */
    private fun frameRotation(helper: Camera2Helper): Int {
        val sensor = helper.sensorOrientation
        return if (isFrontCamera) {
            when (sensor) {
                90 -> 2
                270 -> 1
                else -> 0
            }
        } else {
            if (sensor == 90) 2 else 1
        }
    }

    private fun flipCamera() {
        if (!nosmaiStarted) return
        isFrontCamera = !isFrontCamera
        runCatching { camera?.stopCamera() }
        startCamera()
    }

    /** Forward every filtered frame from Nosmai to ZEGO. */
    private fun startPushingFrames() {
        if (!nosmaiStarted || pushing || stopped) return
        pushing = true
        framesSinceTick.set(0)
        main.postDelayed(fpsTick, 1000)
        // DUAL_OUTPUT keeps the on-screen preview and also delivers each processed frame.
        NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)
        NosmaiSDK.setFrameCallback { frame -> sendFrame(frame) }
    }

    private fun stopPushingFrames() {
        if (!pushing) return
        pushing = false
        main.removeCallbacks(fpsTick)
        runCatching {
            NosmaiSDK.setFrameCallback(null)
            NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.PREVIEW_ONLY)
        }
    }

    /** Host FPS = filtered frames Nosmai actually delivers to ZEGO each second. */
    private val fpsTick = object : Runnable {
        override fun run() {
            if (!pushing) return
            fpsText.text = "${framesSinceTick.getAndSet(0)} FPS"
            main.postDelayed(this, 1000)
        }
    }

    /** Nosmai's frame thread, once per processed frame. */
    private fun sendFrame(frame: NosmaiSDK.FrameData?) {
        val engine = engine
        val data = frame?.pixelBuffer
        if (!pushing || engine == null || frame == null || data == null) return
        val width = frame.width
        val height = frame.height
        val bytes = width * height * 3 / 2
        if (frame.format != NOSMAI_FORMAT_I420 || width <= 0 || height <= 0 ||
            width % 2 != 0 || height % 2 != 0 || data.size < bytes) return

        // Encode at the size Nosmai actually produces, so ZEGO never scales or crops.
        if (width != encodeWidth || height != encodeHeight) {
            encodeWidth = width
            encodeHeight = height
            main.post { this.engine?.let { applyEncodeSize(it, width, height) } }
        }

        // Nosmai reuses its byte array, so copy the frame into one of our own buffers.
        var buffer = frameBuffers[frameBufferIndex]
        if (buffer == null || buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes)
            frameBuffers[frameBufferIndex] = buffer
        }
        frameBufferIndex = (frameBufferIndex + 1) % frameBuffers.size
        buffer.clear()
        buffer.put(data, 0, bytes)
        buffer.flip()

        frameParam.width = width
        frameParam.height = height
        frameParam.strides[0] = width
        frameParam.strides[1] = width / 2
        frameParam.strides[2] = width / 2
        frameParam.rotation = 0
        engine.sendCustomVideoCaptureRawData(buffer, bytes, frameParam, System.currentTimeMillis())
        framesSinceTick.incrementAndGet()
    }

    // ---- Teardown ----

    private fun stopSession() {
        if (stopped) return
        stopped = true
        main.removeCallbacksAndMessages(null)
        if (isHost) {
            pushing = false
            runCatching { NosmaiSDK.setFrameCallback(null) }
            runCatching { camera?.stopCamera() }
            camera = null
            engine?.stopPublishingStream()
        } else {
            playingStreamId?.let { engine?.stopPlayingStream(it) }
        }
        engine?.logoutRoom()
        engine?.setEventHandler(null)
        engine = null
        ZegoExpressEngine.destroyEngine(null)

        effectsPanel?.release()
        if (nosmaiStarted) {
            nosmaiStarted = false
            // cleanup() is the full teardown: it clears the effects and stops processing.
            runCatching { NosmaiSDK.cleanup() }
        }
    }

    // ---- Permissions ----

    private fun hasPermissions() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() = ActivityCompat.requestPermissions(
        this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (hasPermissions()) startNosmaiCamera() else setStatus("Camera and microphone permission are required")
    }
}
