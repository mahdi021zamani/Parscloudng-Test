package com.v2ray.ang.ui.simple

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small private diagnostic log for reproducing personal-config import lifecycle issues.
 * It deliberately accepts only authored event descriptions; config text and URLs are never stored.
 */
object ImportDiagnosticLog {
    private const val FILE_NAME = "parscloud_import_diagnostic.txt"
    private const val MAX_BYTES = 256 * 1024

    @Synchronized
    fun record(context: Context, event: String) {
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val retained = file.readText(Charsets.UTF_8).takeLast(MAX_BYTES / 2)
                file.writeText(retained, Charsets.UTF_8)
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$timestamp | $event\n", Charsets.UTF_8)
        }
    }

    @Synchronized
    fun read(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText(Charsets.UTF_8)
        else "No diagnostic events have been recorded yet.\n"
    }
}
