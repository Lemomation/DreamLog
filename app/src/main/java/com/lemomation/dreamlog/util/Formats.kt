package com.lemomation.dreamlog.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formats {
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm"

    fun timestamp(epochMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.getDefault()).format(Date(epochMillis))

    fun defaultDreamTitle(epochMillis: Long = System.currentTimeMillis()): String =
        timestamp(epochMillis)

    fun duration(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(safe)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(Locale.US, minutes, seconds)
    }

    fun clockTime(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
}
