package android.content

import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Desktop-Ersatz: Uris sind hier immer Dateien (file:). */
open class ContentResolver {
    open fun openInputStream(uri: Uri): InputStream? = uri.toFile()?.takeIf { it.isFile }?.inputStream()

    open fun openOutputStream(uri: Uri, mode: String = "w"): OutputStream? =
        uri.toFile()?.let { f -> f.parentFile?.mkdirs(); f.outputStream() }

    open fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val f = uri.toFile() ?: return null
        return Cursor(mapOf(OpenableColumns.DISPLAY_NAME to f.name, OpenableColumns.SIZE to f.length().toString()))
    }

    private fun Uri.toFile(): File? = if (scheme == "file" || scheme == null) path?.let(::File) else null
}
