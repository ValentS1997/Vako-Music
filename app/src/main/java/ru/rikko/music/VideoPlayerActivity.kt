package ru.rikko.music

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.graphics.toArgb

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_DURATION = "video_duration"
        const val EXTRA_PLAYLIST = "video_playlist"
        const val EXTRA_START_POSITION = "video_start_position"
        const val EXTRA_QUEUE = "video_queue"
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var gestures: GestureDetector
    private lateinit var scaleGestures: ScaleGestureDetector
    private lateinit var videoUri: Uri
    private lateinit var titleView: TextView
    private lateinit var gestureLevel: TextView
    private lateinit var gestureProgress: android.widget.ProgressBar
    private lateinit var gestureOverlay: LinearLayout
    private var gestureMode = 0 // 0: none, 1: brightness, 2: volume, 3: seek
    private var seekPreview = 0L
    private var seekStart = 0L
    private var seekWasPlaying = false
    private var lastSeekApplied = -1L
    private var lastSeekAt = 0L
    private var frameDurationMs = 33L
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastMultiX = 0f
    private var lastMultiY = 0f
    private var pinchActive = false
    private var showGestureLevels = true
    private val history by lazy { VideoHistoryStore(this) }
    private var subtitleUri: Uri? = null
    private var holdSpeed = false
    private var enteringPip = false
    private var selectedSpeed = 1f
    private var playlistItems: List<String> = emptyList()
    private var playlistIndex = -1
    private lateinit var previousButton: TextView
    private lateinit var nextButton: TextView
    private val preferences by lazy { getSharedPreferences("vako_video", MODE_PRIVATE) }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            subtitleUri = uri
            val position = player.currentPosition
            prepare(position)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = intent.data ?: run { finish(); return }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        playlistItems = intent.getStringArrayListExtra(EXTRA_QUEUE).orEmpty()
        if (playlistItems.isEmpty()) intent.getStringExtra(EXTRA_PLAYLIST)?.let { name ->
            playlistItems = VideoPlaylistStore(this).get(name)?.items.orEmpty()
        }
        playlistIndex = playlistItems.indexOf(videoUri.toString())
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        player = ExoPlayer.Builder(this).build()
        player.setSeekParameters(SeekParameters.EXACT)
        showGestureLevels = preferences.getBoolean("show_gesture_levels", true)
        player.volume = preferences.getFloat("app_volume", 1f).coerceIn(0f, 1f)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (Build.VERSION.SDK_INT >= 31) {
                    setPictureInPictureParams(PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(16, 9))
                        .setAutoEnterEnabled(isPlaying).build())
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@VideoPlayerActivity, "Не удалось воспроизвести видео: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    preferences.edit().remove(videoUri.toString()).apply()
                    history.record(videoUri.toString(), titleView.text.toString(), 0L)
                    if (playlistIndex >= 0 && playlistIndex < playlistItems.lastIndex) switchVideo(playlistIndex + 1)
                }
            }
        })
        scaleGestures = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                if (zoom == 1f) { panX = 0f; panY = 0f }
                applyZoom()
                return true
            }
        })
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { gestureMode = 0; return true }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1f) {
                    zoom = 1f; panX = 0f; panY = 0f; applyZoom()
                    return true
                }
                val width = playerView.width.toFloat().coerceAtLeast(1f)
                when {
                    e.x < width / 3 -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    e.x > width * 2 / 3 -> player.seekTo(player.currentPosition + 10_000)
                    else -> if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (pinchActive || gestureMode != 0) return
                val height = playerView.height.toFloat().coerceAtLeast(1f)
                if (e.y in height * 0.15f..height * 0.85f && !holdSpeed) {
                    holdSpeed = true
                    player.setPlaybackSpeed(2f)
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || pinchActive || e2.pointerCount > 1) return false
                val height = playerView.height.toFloat().coerceAtLeast(1f)
                if (e1.y < height * 0.15f || e1.y > height * 0.85f) return false
                if (gestureMode == 0) {
                    gestureMode = if (kotlin.math.abs(e2.x - e1.x) > kotlin.math.abs(e2.y - e1.y) * 1.2f) 3
                        else if (e1.x < playerView.width / 2f) 1 else 2
                    seekStart = player.currentPosition
                    seekPreview = seekStart
                    if (gestureMode == 3) {
                        seekWasPlaying = player.isPlaying
                        player.pause()
                        lastSeekApplied = -1L
                    }
                }
                if (gestureMode == 3) {
                    val duration = player.duration.takeIf { it > 0 } ?: return false
                    val pixelsPerFrame = 4f * resources.displayMetrics.density
                    val frameDelta = ((e2.x - e1.x) / pixelsPerFrame).toLong()
                    val snapped = (seekStart + frameDelta * frameDurationMs).coerceIn(0, duration)
                    seekPreview = snapped
                    val now = SystemClock.uptimeMillis()
                    if (snapped != lastSeekApplied && now - lastSeekAt >= 35) {
                        player.seekTo(snapped)
                        lastSeekApplied = snapped
                        lastSeekAt = now
                    }
                    showLevel("${VideoStore.formatDuration(snapped)} / ${VideoStore.formatDuration(duration)} · кадр ≈${snapped / frameDurationMs}",
                        (snapped * 100 / duration).toInt())
                } else if (gestureMode == 1) {
                    val attributes = window.attributes
                    val current = if (attributes.screenBrightness < 0) 0.5f else attributes.screenBrightness
                    attributes.screenBrightness = (current + distanceY / height * 2).coerceIn(0.02f, 1f)
                    window.attributes = attributes
                    showLevel("Яркость окна · ${(attributes.screenBrightness * 100).toInt()}%", (attributes.screenBrightness * 100).toInt())
                } else {
                    player.volume = (player.volume + distanceY / height * 2).coerceIn(0f, 1f)
                    showLevel("Громкость видео · ${(player.volume * 100).toInt()}%", (player.volume * 100).toInt())
                }
                return true
            }
        })
        playerView = object : PlayerView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    pinchActive = true
                    gestureMode = 0
                    gestureOverlay.visibility = View.GONE
                    val x = (0 until event.pointerCount).sumOf { event.getX(it).toDouble() }.toFloat() / event.pointerCount
                    val y = (0 until event.pointerCount).sumOf { event.getY(it).toDouble() }.toFloat() / event.pointerCount
                    if (event.actionMasked == MotionEvent.ACTION_MOVE && zoom > 1f) {
                        panX += x - lastMultiX; panY += y - lastMultiY; applyZoom()
                    }
                    lastMultiX = x; lastMultiY = y
                }
                scaleGestures.onTouchEvent(event)
                if (!pinchActive) gestures.onTouchEvent(event)
                if (holdSpeed && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                    holdSpeed = false
                    this@VideoPlayerActivity.player.setPlaybackSpeed(selectedSpeed)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (gestureMode == 3 && event.actionMasked == MotionEvent.ACTION_UP) {
                        this@VideoPlayerActivity.player.seekTo(seekPreview)
                        if (seekWasPlaying) this@VideoPlayerActivity.player.play()
                    }
                    seekWasPlaying = false
                    val consumed = gestureMode != 0 || pinchActive
                    gestureMode = 0; pinchActive = false; gestureOverlay.visibility = View.GONE
                    if (consumed) return true
                }
                return if (gestureMode != 0 || pinchActive) true else super.dispatchTouchEvent(event)
            }
        }.apply {
            this.player = this@VideoPlayerActivity.player
            useController = true
            setShowNextButton(false)
            setShowPreviousButton(false)
            setBackgroundColor(Color.BLACK)
            resizeMode = preferences.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }
        val settings = AppPreferences(this)
        val accentColor = ThemePalette.resolve(
            settings.number("theme_base", 2), settings.number("theme_accent", 0),
            settings.enabled("theme_custom", false),
            settings.string("theme_background", "#101314"),
            settings.string("theme_surface", "#1C2021"),
            settings.string("theme_text", "#F0F3F1"),
            settings.string("theme_accent_hex", "#8FCF52")
        ).accent.toArgb()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 0, 8.dp, 0)
            setBackgroundColor(0xCC111711.toInt())
        }
        fun button(label: String, action: (View) -> Unit) {
            toolbar.addView(TextView(this).apply {
                text = label
                textSize = 24f
                setTextColor(accentColor)
                gravity = Gravity.CENTER
                setPadding(7.dp, 0, 7.dp, 0)
                setOnClickListener { action(it) }
                contentDescription = when (label) {
                    "⋮" -> "Настройки видео"
                    "▣" -> "Маленькое окно"
                    "←" -> "Назад"
                    "‹" -> "Предыдущее видео"
                    "›" -> "Следующее видео"
                    else -> label
                }
            }, LinearLayout.LayoutParams(48.dp, 48.dp))
        }
        button("←") { finish() }
        titleView = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: videoTitle(videoUri)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (playlistIndex >= 0) {
            button("‹") { if (playlistIndex > 0) switchVideo(playlistIndex - 1) }
            button("›") { if (playlistIndex < playlistItems.lastIndex) switchVideo(playlistIndex + 1) }
        }
        button("⋮") { anchor ->
            popup(anchor, listOf("Скорость", "Громкость приложения", "Размер видео", "Субтитры", "Ориентация")) { choice ->
                when (choice) {
                    0 -> android.app.AlertDialog.Builder(this).setTitle("Скорость")
                        .setItems(arrayOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×")) { _, index ->
                            selectedSpeed = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)[index]
                            if (!holdSpeed) player.setPlaybackSpeed(selectedSpeed)
                        }.show()
                    1 -> {
                        val slider = SeekBar(this).apply { max = 100; progress = (player.volume * 100).toInt() }
                        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { player.volume = progress / 100f }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        })
                        android.app.AlertDialog.Builder(this).setTitle("Громкость приложения")
                            .setMessage("Кнопки телефона управляют системной громкостью. Здесь и жестом справа меняется только звук видео.")
                            .setView(slider).setPositiveButton("Готово", null).show()
                    }
                    2 -> android.app.AlertDialog.Builder(this).setTitle("Размер видео")
                        .setItems(arrayOf("Вписать", "Растянуть", "Заполнить с обрезкой")) { _, index ->
                            playerView.resizeMode = intArrayOf(
                                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                AspectRatioFrameLayout.RESIZE_MODE_FILL,
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            )[index]
                            preferences.edit().putInt("resize_mode", playerView.resizeMode).apply()
                            zoom = 1f; panX = 0f; panY = 0f; applyZoom()
                        }.show()
                    3 -> subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "*/*"))
                    4 -> android.app.AlertDialog.Builder(this).setTitle("Ориентация")
                        .setItems(arrayOf("Автоповорот", "Портрет", "Альбомная")) { _, index ->
                            requestedOrientation = intArrayOf(
                                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            )[index]
                        }.show()
                }
            }
        }
        button("▣") {
            enterPip()
        }
        root.addView(toolbar)
        gestureLevel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setBackgroundColor(0xCC172117.toInt()); setPadding(12.dp, 8.dp, 12.dp, 4.dp)
        }
        gestureProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        gestureOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(gestureLevel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(gestureProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 5.dp))
        }
        val videoContainer = FrameLayout(this).apply {
            clipChildren = true
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(gestureOverlay, FrameLayout.LayoutParams(260.dp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        fun navigationButton(label: String, description: String, gravity: Int, action: () -> Unit): TextView = TextView(this).apply {
            text = label; textSize = 30f; setTextColor(Color.WHITE)
            contentDescription = description
            this.gravity = Gravity.CENTER
            setBackgroundColor(0xAA172117.toInt())
            setOnClickListener { action() }
            videoContainer.addView(this, FrameLayout.LayoutParams(48.dp, 48.dp, gravity).apply { bottomMargin = 82.dp; marginStart = 24.dp; marginEnd = 24.dp })
        }
        previousButton = navigationButton("⏮", "Предыдущее видео", Gravity.BOTTOM or Gravity.START) {
            if (playlistIndex > 0) switchVideo(playlistIndex - 1)
        }
        nextButton = navigationButton("⏭", "Следующее видео", Gravity.BOTTOM or Gravity.END) {
            if (playlistIndex in 0 until playlistItems.lastIndex) switchVideo(playlistIndex + 1)
        }
        updateNavigationButtons()
        root.addView(videoContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        val saved = intent.getLongExtra(EXTRA_START_POSITION, -1L).takeIf { it >= 0 }
            ?: preferences.getLong(videoUri.toString(), 0L)
        val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
        if (intent.hasExtra(EXTRA_START_POSITION)) prepare(saved)
        else if (saved > 10_000 && (duration <= 0 || saved < duration - 10_000)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Продолжить просмотр?")
                .setMessage("С ${VideoStore.formatDuration(saved)}")
                .setPositiveButton("Продолжить") { _, _ -> prepare(saved) }
                .setNegativeButton("С начала") { _, _ -> prepare(0) }
                .setOnCancelListener { prepare(saved) }
                .show()
        } else prepare(0)
    }

    private fun prepare(position: Long) {
        history.record(videoUri.toString(), titleView.text.toString(), position)
        frameDurationMs = estimateFrameDuration(videoUri)
        val builder = MediaItem.Builder().setUri(videoUri)
        subtitleUri?.let { uri ->
            val mime = if (uri.toString().substringBefore('?').endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLanguage("und").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        player.setMediaItem(builder.build())
        player.prepare()
        player.seekTo(position)
        player.play()
    }

    private fun switchVideo(index: Int) {
        savePosition()
        playlistIndex = index
        videoUri = Uri.parse(playlistItems[index])
        titleView.text = videoTitle(videoUri)
        subtitleUri = null
        zoom = 1f; panX = 0f; panY = 0f; applyZoom()
        prepare(preferences.getLong(videoUri.toString(), 0L))
        updateNavigationButtons()
    }

    private fun videoTitle(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "Видео"

    private fun updateNavigationButtons() {
        previousButton.visibility = if (playlistIndex > 0) View.VISIBLE else View.GONE
        nextButton.visibility = if (playlistIndex in 0 until playlistItems.lastIndex) View.VISIBLE else View.GONE
    }

    private fun showLevel(label: String, progress: Int) {
        if (!showGestureLevels) return
        gestureLevel.text = label
        gestureProgress.progress = progress.coerceIn(0, 100)
        gestureOverlay.visibility = View.VISIBLE
    }

    private fun applyZoom() {
        if (!::playerView.isInitialized) return
        val surface = playerView.videoSurfaceView ?: return
        val maxX = playerView.width * (zoom - 1f) / 2f
        val maxY = playerView.height * (zoom - 1f) / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        surface.scaleX = zoom; surface.scaleY = zoom
        surface.translationX = panX; surface.translationY = panY
    }

    private fun estimateFrameDuration(uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(this, uri)
            val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (frames > 0 && duration > 0) (duration / frames).coerceIn(10, 100) else 33L
        }
    }.getOrDefault(33L)

    private fun popup(anchor: View, entries: List<String>, action: (Int) -> Unit) {
        PopupMenu(this, anchor).apply {
            entries.forEachIndexed { index, label -> menu.add(0, index, index, label) }
            setOnMenuItemClickListener { action(it.itemId); true }
            show()
        }
    }

    override fun onPause() {
        savePosition()
        preferences.edit().putFloat("app_volume", player.volume).apply()
        if (isFinishing) player.pause()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        if (::player.isInitialized && player.isPlaying && !isFinishing) enterPip()
        super.onUserLeaveHint()
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature("android.software.picture_in_picture")) return
        enteringPip = runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9)).build()) }.getOrDefault(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enteringPip = false
        playerView.useController = !isInPictureInPictureMode
    }

    private fun savePosition() {
        if (::player.isInitialized && player.duration > 0) {
            val position = player.currentPosition
            val valid = position > 5_000 && position < player.duration - 10_000
            history.record(videoUri.toString(), titleView.text.toString(), if (valid) position else 0L)
            preferences.edit().apply {
                if (valid) putLong(videoUri.toString(), position)
                else remove(videoUri.toString())
            }.apply()
        }
    }

    override fun onDestroy() {
        savePosition()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
