package androidx.activity.result.contract

import android.net.Uri
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Desktop-Ersatz: Vertraege fuer Datei oeffnen und speichern, ausgefuehrt mit dem Dateidialog des Systems. */
abstract class ActivityResultContract<I, O> {
    abstract fun run(input: I): O
}

object ActivityResultContracts {
    /** Datei oeffnen; die Eingabe sind MIME-Typen (auf dem Desktop ohne Filter). */
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun run(input: Array<String>): Uri? {
            val d = FileDialog(null as Frame?, "Datei öffnen", FileDialog.LOAD)
            d.isVisible = true
            val name = d.file ?: return null
            return Uri.fromFile(File(d.directory, name))
        }
    }

    /** Datei speichern; die Eingabe ist der vorgeschlagene Dateiname. */
    class CreateDocument(private val mime: String) : ActivityResultContract<String, Uri?>() {
        override fun run(input: String): Uri? {
            val d = FileDialog(null as Frame?, "Speichern unter", FileDialog.SAVE)
            d.file = input
            d.isVisible = true
            val name = d.file ?: return null
            val ext = input.substringAfterLast('.', "")
            val fixed = if (ext.isNotEmpty() && !name.endsWith(".$ext", ignoreCase = true)) "$name.$ext" else name
            return Uri.fromFile(File(d.directory, fixed))
        }
    }
}
