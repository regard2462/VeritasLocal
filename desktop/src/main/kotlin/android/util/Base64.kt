package android.util

/** Desktop-Ersatz fuer android.util.Base64 (nur decode mit den genutzten Schaltern). */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    @JvmStatic fun decode(str: String, flags: Int): ByteArray {
        val clean = str.filterNot { it == '\n' || it == '\r' }
        val dec = if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()
        val padded = if (clean.length % 4 == 0) clean else clean + "=".repeat(4 - clean.length % 4)
        return dec.decode(padded)
    }
}
