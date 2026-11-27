package android.content

import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Desktop-Ersatz fuer den kleinen Teil von android.content.Context, den der gemeinsame Code nutzt: Datenordner,
 * Einstellungen, Dateizugriff ueber Uri und Speicherabfrage. Keine Android-Funktion darueber hinaus.
 */
open class Context(val dataDir: File) {
    open val filesDir: File get() = dataDir.apply { mkdirs() }
    open val applicationContext: Context get() = this
    open val applicationInfo: ApplicationInfo = ApplicationInfo()
    open val contentResolver: ContentResolver = ContentResolver()

    private val prefs = HashMap<String, SharedPreferences>()

    @Synchronized
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FileSharedPreferences(File(filesDir, "$name.properties")) }

    open fun getSystemService(name: String): Any? = when (name) {
        ACTIVITY_SERVICE -> android.app.ActivityManager()
        else -> null
    }

    companion object {
        const val MODE_PRIVATE = 0
        const val ACTIVITY_SERVICE = "activity"
    }
}
