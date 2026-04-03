package io.github.todotxt.app.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal file-based debug logger.
 * Writes to <app cache>/debug.log so the user can copy/share it.
 */
object DebugLog {

    private const val FILE_NAME = "debug.log"
    private val TS = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun clear(context: Context) {
        file(context).delete()
    }

    fun d(context: Context, msg: String) {
        append(context, "D", msg)
    }

    fun e(context: Context, msg: String, t: Throwable? = null) {
        append(context, "E", msg)
        if (t != null) {
            append(context, "E", t.toString())
            for (line in t.stackTrace.take(8)) append(context, "E", "  at $line")
        }
    }

    fun read(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("(log empty)")

    private fun file(context: Context): File =
        File(context.cacheDir, FILE_NAME)

    private fun append(context: Context, level: String, msg: String) {
        val line = "${TS.format(Date())} $level $msg\n"
        file(context).appendText(line)
    }
}
