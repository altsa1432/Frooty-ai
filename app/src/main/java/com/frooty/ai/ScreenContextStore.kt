package com.frooty.ai

import android.content.Context

class ScreenContextStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isCaptureEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setCaptureEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Could not update screen-reading preference"
        }
        if (!enabled) clear()
    }

    fun save(text: String) {
        if (!isCaptureEnabled()) return
        check(preferences.edit().putString(KEY_TEXT, text.take(MAX_CHARS)).commit()) {
            "Could not save screen text"
        }
    }

    fun current(): String = preferences.getString(KEY_TEXT, "").orEmpty()

    fun clear() {
        check(preferences.edit().remove(KEY_TEXT).commit()) { "Could not clear screen text" }
    }

    private companion object {
        const val PREFERENCES_NAME = "frooty_screen_context"
        const val KEY_ENABLED = "capture_enabled"
        const val KEY_TEXT = "latest_external_screen"
        const val MAX_CHARS = 6000
    }
}
