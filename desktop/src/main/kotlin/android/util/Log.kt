package android.util

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop-Ersatz fuer android.util.Log: schreibt nach stderr und, wenn veritas.log gesetzt ist, zusaetzlich in
 * diese Datei (Pruefprotokoll wie logcat).
 */
object Log {
    private val file: File? = System.getProperty("veritas.log")?.let(::File)
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    private fun out(level: Char, tag: String, msg: String, tr: Throwable? = null): Int {
        val line = "${LocalDateTime.now().format(fmt)} $level $tag: $msg" + (tr?.let { "\n" + it.stackTraceToString() } ?: "")
        System.err.println(line)
        file?.let { f -> synchronized(this) { runCatching { f.appendText(line + "\n") } } }
        return 0
    }

    @JvmStatic fun v(tag: String, msg: String) = out('V', tag, msg)
    @JvmStatic fun d(tag: String, msg: String) = out('D', tag, msg)
    @JvmStatic fun i(tag: String, msg: String) = out('I', tag, msg)
    @JvmStatic fun w(tag: String, msg: String) = out('W', tag, msg)
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?) = out('W', tag, msg, tr)
    @JvmStatic fun e(tag: String, msg: String) = out('E', tag, msg)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?) = out('E', tag, msg, tr)
}
