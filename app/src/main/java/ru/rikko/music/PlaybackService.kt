package ru.rikko.music

import android.app.Notification
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.IBinder

/** Owns audio and its Android media session while the activity is in the background. */
class PlaybackService : Service() {
    interface Listener {
        fun onPrepared(duration: Int)
        fun onCompletion()
        fun onError()
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
    }

    inner class LocalBinder : Binder() { val service: PlaybackService get() = this@PlaybackService }
    private val binder = LocalBinder()
    var listener: Listener? = null
    var player: MediaPlayer? = null
        private set
    var prepared = false
        private set
    var title = ""
        private set
    var artist = ""
        private set
    private lateinit var session: MediaSession
    private val notificationId = 11
    private val channelId = "rikko_playback"

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Воспроизведение", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "RikkoPlayback")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (listener != null) listener?.onPlayPause() else toggle() }
            override fun onPause() { if (listener != null) listener?.onPlayPause() else toggle() }
            override fun onSkipToNext() { listener?.onNext() ?: advanceOffline(1) }
            override fun onSkipToPrevious() { listener?.onPrevious() ?: advanceOffline(-1) }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        })
        session.isActive = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play_pause" -> if (listener != null) listener?.onPlayPause() else toggle()
            "next" -> listener?.onNext() ?: advanceOffline(1)
            "previous" -> listener?.onPrevious() ?: advanceOffline(-1)
        }
        return START_STICKY
    }

    fun play(url: String?, uri: Uri?, newTitle: String, newArtist: String) {
        releasePlayer()
        title = newTitle
        artist = newArtist
        updateSession()
        try {
            val next = MediaPlayer()
            player = next
            if (uri != null) next.setDataSource(this, uri) else next.setDataSource(url!!)
            next.setOnPreparedListener {
                prepared = true
                it.start()
                updateSession()
                listener?.onPrepared(it.duration.coerceAtLeast(0))
            }
            next.setOnCompletionListener {
                updateSession()
                listener?.onCompletion() ?: advanceOffline(1)
            }
            next.setOnErrorListener { _, _, _ ->
                updateSession()
                listener?.onError()
                true
            }
            next.prepareAsync()
            startForeground(notificationId, notification())
        } catch (_: Exception) {
            releasePlayer()
            listener?.onError()
        }
    }

    fun toggle() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) p.pause() else p.start()
        updateSession()
    }

    fun pause() {
        if (prepared && player?.isPlaying == true) player?.pause()
        updateSession()
    }

    fun seekTo(position: Int) {
        if (prepared) player?.seekTo(position.coerceAtLeast(0))
        updateSession()
    }

    fun refresh() = updateSession()

    fun reset() {
        releasePlayer()
        updateSession()
    }

    fun stop() {
        releasePlayer()
        title = ""
        artist = ""
        updateSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        prepared = false
    }

    // Android exempts media-session notifications from POST_NOTIFICATIONS consent.
    @SuppressLint("NotificationPermission")
    private fun updateSession() {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (prepared) player?.duration?.toLong() ?: 0 else 0)
            .build())
        val isPlaying = prepared && player?.isPlaying == true
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(state, if (prepared) player?.currentPosition?.toLong() ?: 0 else 0, if (isPlaying) 1f else 0f)
            .build())
        if (title.isNotBlank()) getSystemService(NotificationManager::class.java).notify(notificationId, notification())
    }

    private fun action(name: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, PlaybackService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "Vako Music" })
            .setContentText(artist)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Назад", action("previous", 1)).build())
            .addAction(Notification.Action.Builder(
                if (prepared && player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (prepared && player?.isPlaying == true) "Пауза" else "Играть", action("play_pause", 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Далее", action("next", 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun advanceOffline(direction: Int) {
        val saved = SavedQueueStore(this)
        val snapshot = saved.loadCurrent() ?: return
        val index = (snapshot.index + direction).coerceIn(0, snapshot.entries.lastIndex)
        if (index == snapshot.index) return
        val entry = snapshot.entries[index]
        saved.saveCurrent(snapshot.copy(index = index))
        entry.localUri?.let { play(null, Uri.parse(it), titleForUri(Uri.parse(it)), "На устройстве"); return }
        val track = entry.remote ?: return
        Thread {
            try {
                val source = YandexMusic.getAudioSource(track, Quality.MP3_320, TokenStore(this).read())
                android.os.Handler(mainLooper).post { play(source.url, null, track.title, track.artist) }
            } catch (_: Exception) { }
        }.start()
    }

    private fun titleForUri(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(android.provider.MediaStore.Audio.Media.TITLE), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "Трек"
        } ?: "Трек"
    } catch (_: Exception) { "Трек" }

    override fun onDestroy() {
        releasePlayer()
        session.release()
        super.onDestroy()
    }
}
