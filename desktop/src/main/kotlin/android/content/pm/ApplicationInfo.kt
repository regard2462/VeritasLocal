package android.content.pm

/** Desktop-Ersatz: Ordner der nativen Bibliotheken (llama.cpp) kommt vom Startprogramm. */
class ApplicationInfo {
    @JvmField var nativeLibraryDir: String = System.getProperty("veritas.nativeDir").orEmpty()
    @JvmField var flags: Int = if (System.getProperty("veritas.debug") == "true") FLAG_DEBUGGABLE else 0

    companion object {
        const val FLAG_DEBUGGABLE = 2
    }
}
