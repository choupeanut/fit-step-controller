package com.choupeanut.fitstepcontroller.data

import android.content.Context
import java.time.Instant

class StepWriteCursorStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadDirectCursor(): Instant? {
        val epochMillis = preferences.getLong(KEY_DIRECT_CURSOR_MS, Long.MIN_VALUE)
        return if (epochMillis == Long.MIN_VALUE) null else Instant.ofEpochMilli(epochMillis)
    }

    fun saveDirectCursor(cursor: Instant) {
        preferences.edit().putLong(KEY_DIRECT_CURSOR_MS, cursor.toEpochMilli()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "step-write-cursors"
        private const val KEY_DIRECT_CURSOR_MS = "direct-cursor-ms"
    }
}
