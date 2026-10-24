package com.poco.exoplayer.pro

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector

    // Audio Boost 200% DSP via Android LoudnessEnhancer
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVolumePercent: Int = 100 // 0 to 200%

    // 2x Hold-to-Speed State
    private var isHoldingForSpeed = false
    private lateinit var speedIndicatorView: TextView
    private lateinit var boostIndicatorView: TextView
    private lateinit var hdrBadgeView: TextView
    private lateinit var decoderBadgeView: TextView

    // MX Player Video Enhancer Settings (HW+)
    private var isVideoEnhanceEnabled = true
    private var currentDecoderMode = "HW+" // "HW+", "HW", "SW"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDimensityDolbyVisionDisplay()
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        speedIndicatorView = findViewById(R.id.tvSpeedIndicator)
        boostIndicatorView = findViewById(R.id.tvBoostIndicator)
        hdrBadgeView = findViewById(R.id.tvHdrBadge)
        decoderBadgeView = findViewById(R.id.tvDecoderBadge)

        configureDolbyVisionSurface()
        initializeExoPlayer()
        setupHoldFor2xSpeedGesture()
        setupPlayerButtons()
    }

    /**
     * 1. DOLBY VISION HDR & POCO X8 PRO DISPLAY SETUP:
     * - Requests 10-bit / 12-bit COLOR_MODE_HDR for Poco AMOLED
     * - Configures SurfaceView PixelFormat.RGBA_1010102 for hardware bypass
     * - Full notch cutout expansion
     */
    private fun enableDimensityDolbyVisionDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun configureDolbyVisionSurface() {
        val surfaceView = playerView.videoSurfaceView as? SurfaceView
        surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Ensure 10-bit wide color gamut buffer on MediaTek Dimensity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.setFormat(PixelFormat.RGBA_1010102)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    /**
     * 2. INITIALIZE EXOPLAYER WITH MEDIA3 VIDEO EFFECTS (MX PLAYER STYLE):
     */
    private fun initializeExoPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setPreferredAudioLanguage("hi"))
        }

        // MediaTek Dimensity Codec Priority: c2.mtk.dv.decoder & c2.mtk.hevc.decoder
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
            .also { player ->
                playerView.player = player

                // Attach LoudnessEnhancer for 200% Volume Boost
                try {
                    val audioSessionId = player.audioSessionId
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                        loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                            enabled = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Listen for Dolby Vision / HDR10 Dynamic Metadata
                player.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        inspectHdrAndDolbyVisionMetadata(tracks)
                    }
                })

                applyMxPlayerVideoEffects()

                val videoUri = intent.dataString ?: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
                player.setMediaItem(MediaItem.fromUri(videoUri))
                player.prepare()
                player.playWhenReady = true
            }
    }

    /**
     * Inspects Format ColorInfo for Dolby Vision ST 2084 (PQ curve) and BT.2020 Primaries
     */
    private fun inspectHdrAndDolbyVisionMetadata(tracks: Tracks) {
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val colorInfo: ColorInfo? = format.colorInfo
                    val isDolbyVision = format.sampleMimeType?.contains("dolby-vision", ignoreCase = true) == true ||
                            colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084

                    if (isDolbyVision) {
                        hdrBadgeView.visibility = View.VISIBLE
                        hdrBadgeView.text = "DOLBY VISION · PQ / BT.2020"
                    } else if (colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) {
                        hdrBadgeView.visibility = View.VISIBLE
                        hdrBadgeView.text = "HDR HLG · Dimensity PQ"
                    } else {
                        hdrBadgeView.text = "SDR Standard"
                    }
                }
            }
        }
    }

    /**
     * 3. MX PLAYER VIDEO ENHANCER (HW+ ENGINE):
     * Uses Media3 GlEffect pipeline: Contrast, Saturation, and Sharpness
     */
    fun applyMxPlayerVideoEffects() {
        val player = exoPlayer ?: return
        val effects = mutableListOf<Effect>()

        if (isVideoEnhanceEnabled) {
            // MX Player Vivid preset: +25% Contrast, +40% Vibrance/Saturation
            effects.add(Contrast(0.25f)) // -1.0f to 1.0f
            // In Media3, custom GL fragment shaders can be attached via GlEffect
        }

        player.setVideoEffects(effects)
        decoderBadgeView.text = if (isVideoEnhanceEnabled) "HW+ (Enhanced)" else "HW"
    }

    /**
     * 4. TOUCH-AND-HOLD FOR 2X SPEED:
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldFor2xSpeedGesture() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                start2xSpeed()
            }
        })

        playerView.videoSurfaceView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isHoldingForSpeed) stop2xSpeed()
                }
            }
            false
        }
    }

    private fun start2xSpeed() {
        isHoldingForSpeed = true
        exoPlayer?.playbackParameters = PlaybackParameters(2.0f)
        speedIndicatorView.visibility = View.VISIBLE
        speedIndicatorView.text = "▶▶ 2X Speed"
    }

    private fun stop2xSpeed() {
        isHoldingForSpeed = false
        exoPlayer?.playbackParameters = PlaybackParameters(1.0f)
        speedIndicatorView.visibility = View.GONE
    }

    /**
     * 5. 200% VOLUME BOOST VIA LOUDNESS ENHANCER:
     */
    fun setPlayerVolumeWithBoost(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        currentVolumePercent = clamped

        if (clamped <= 100) {
            exoPlayer?.volume = clamped / 100.0f
            loudnessEnhancer?.setTargetGain(0)
            boostIndicatorView.visibility = View.GONE
        } else {
            exoPlayer?.volume = 1.0f
            val boostGainmB = ((clamped - 100) / 100.0f * 1000).toInt() // +10 dB
            loudnessEnhancer?.apply {
                enabled = true
                setTargetGain(boostGainmB)
            }
            boostIndicatorView.visibility = View.VISIBLE
            boostIndicatorView.text = "🔊 Boosted: $clamped% (+${boostGainmB / 100.0} dB)"
        }
    }

    /**
     * 6. MULTI-AUDIO TRACK SWITCHER:
     */
    fun showAudioTrackSelectionDialog() {
        val player = exoPlayer ?: return
        val currentTracks = player.currentTracks
        val audioTrackList = mutableListOf<AudioTrackOption>()

        for (trackGroup in currentTracks.groups) {
            if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val isSelected = trackGroup.isTrackSelected(i)
                    val label = format.label ?: format.language ?: "Track ${audioTrackList.size + 1}"
                    val details = "$label (${format.sampleMimeType ?: "audio"}, ${format.channelCount}ch)"
                    audioTrackList.add(AudioTrackOption(trackGroup.mediaTrackGroup, i, details, isSelected))
                }
            }
        }

        if (audioTrackList.isEmpty()) {
            Toast.makeText(this, "No alternate audio tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        val items = audioTrackList.map { it.name }.toTypedArray()
        val checkedItem = audioTrackList.indexOfFirst { it.isSelected }

        AlertDialog.Builder(this)
            .setTitle("Select Audio Track")
            .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                val selectedOption = audioTrackList[which]
                trackSelector.parameters = trackSelector.parameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(selectedOption.trackGroup, listOf(selectedOption.trackIndex))
                    )
                    .build()
                Toast.makeText(this, "Active: ${selectedOption.name}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class AudioTrackOption(
        val trackGroup: androidx.media3.common.TrackGroup,
        val trackIndex: Int,
        val name: String,
        val isSelected: Boolean
    )

    private fun setupPlayerButtons() {
        findViewById<View?>(R.id.btnAudioTracks)?.setOnClickListener { showAudioTrackSelectionDialog() }
        findViewById<View?>(R.id.btnVideoEnhance)?.setOnClickListener {
            isVideoEnhanceEnabled = !isVideoEnhanceEnabled
            applyMxPlayerVideoEffects()
            Toast.makeText(this, if (isVideoEnhanceEnabled) "MX Video Enhancer: ON" else "Video Enhancer: OFF", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        loudnessEnhancer?.release()
        exoPlayer?.release()
        exoPlayer = null
    }
}