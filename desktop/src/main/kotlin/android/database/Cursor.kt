package android.database

import java.io.Closeable

/** Desktop-Ersatz: Ergebnis mit genau einer Zeile. */
class Cursor(private val row: Map<String, String>) : Closeable {
    private val columns = row.keys.toList()
    private var pos = -1
    fun getColumnIndex(name: String): Int = columns.indexOf(name)
    fun moveToFirst(): Boolean { pos = 0; return row.isNotEmpty() }
    fun getString(index: Int): String? = if (pos == 0) columns.getOrNull(index)?.let(row::get) else null
    override fun close() {}
}
