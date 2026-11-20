package ru.rikko.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes explicit PiP actions to the visible player instance. */
class VideoPipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        VideoPlayerActivity.handlePipAction(intent.action)
    }
}
