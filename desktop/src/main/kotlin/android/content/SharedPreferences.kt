package android.content

import java.io.File
import java.util.Properties

/** Desktop-Ersatz fuer android.content.SharedPreferences mit den genutzten Methoden. */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** Speichert Einstellungen als Properties-Datei; Mengen werden mit dem Steuerzeichen 0x1F getrennt. */
internal class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val props = Properties()

    init {
        if (file.exists()) runCatching { file.inputStream().use { props.load(it) } }
    }

    @Synchronized private fun raw(key: String): String? = props.getProperty(key)

    override fun getString(key: String, defValue: String?) = raw(key) ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        raw(key)?.let { v -> if (v.isEmpty()) emptySet() else v.split(SEP).toSet() } ?: defValues
    override fun getInt(key: String, defValue: Int) = raw(key)?.toIntOrNull() ?: defValue
    override fun getLong(key: String, defValue: Long) = raw(key)?.toLongOrNull() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = raw(key)?.toBooleanStrictOrNull() ?: defValue
    override fun contains(key: String) = raw(key) != null

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, String?>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.joinToString(SEP) }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clearAll = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@FileSharedPreferences) {
            if (clearAll) props.clear()
            for ((k, v) in changes) if (v == null) props.remove(k) else props.setProperty(k, v)
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { props.store(it, null) }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.isSuccess
        }
    }

    private companion object { const val SEP = "\u001F" }
}
