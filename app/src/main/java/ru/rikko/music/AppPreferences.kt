package ru.rikko.music

import android.content.Context

/** User-selected sections and playback defaults. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("rikko_modules", Context.MODE_PRIVATE)

    fun enabled(key: String, default: Boolean = true): Boolean = prefs.getBoolean(key, default)

    fun setEnabled(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun downloadQuality(): Quality = runCatching {
        Quality.valueOf(prefs.getString("download_quality", Quality.AUTO.name) ?: Quality.AUTO.name)
    }.getOrDefault(Quality.AUTO)

    fun setDownloadQuality(value: Quality) {
        prefs.edit().putString("download_quality", value.name).apply()
    }

    fun number(key: String, default: Int = 0): Int = prefs.getInt(key, default)

    fun setNumber(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun string(key: String, default: String): String = prefs.getString(key, default) ?: default

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
