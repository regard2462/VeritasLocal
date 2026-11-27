package android.net

import java.io.File
import java.net.URI

/** Desktop-Ersatz fuer android.net.Uri mit den genutzten Teilen, auf Basis von java.net.URI. */
class Uri private constructor(private val raw: String, private val uri: URI?) {
    val scheme: String? get() = uri?.scheme
    val host: String? get() = uri?.host
    val path: String? get() = uri?.path
    val encodedPath: String? get() = uri?.rawPath
    val encodedQuery: String? get() = uri?.rawQuery
    val query: String? get() = uri?.query
    val port: Int get() = uri?.port ?: -1
    val lastPathSegment: String? get() = path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    override fun toString() = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()

    companion object {
        @JvmStatic fun parse(s: String): Uri = Uri(s, runCatching { URI(s) }.getOrElse {
            // Leerzeichen und andere unerlaubte Zeichen kodieren statt abzulehnen
            runCatching { URI(s.replace(" ", "%20").replace("|", "%7C")) }.getOrNull()
        })
        @JvmStatic fun fromFile(f: File): Uri { val u = f.absoluteFile.toURI(); return Uri(u.toString(), u) }
    }
}
