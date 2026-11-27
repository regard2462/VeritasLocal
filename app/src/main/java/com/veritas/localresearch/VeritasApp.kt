package com.veritas.localresearch

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.veritas.localresearch.ui.BackgroundWork
import com.veritas.localresearch.ui.ResearchViewModel

/**
 * Das ViewModel gehoert dem Prozess statt der Activity: wird die App geschlossen, laeuft die Recherche im
 * Vordergrunddienst weiter, und beim erneuten Oeffnen erscheint derselbe Stand.
 */
class VeritasApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val researchViewModel: ResearchViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[ResearchViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        com.veritas.localresearch.docs.Ocr.engine = com.veritas.localresearch.docs.AndroidOcr(this)
        BackgroundWork.hooks = object : BackgroundWork.Hooks {
            override fun started(title: String) { generation.incrementAndGet(); send(ResearchService.ACTION_START, title, "") }
            override fun progress(title: String, detail: String) = send(ResearchService.ACTION_PROGRESS, title, detail)
            override fun finished(title: String, text: String) = send(ResearchService.ACTION_FINISH, title, text)
        }
    }

    /**
     * Nummer des juengsten Laufs. Ein Ende-Signal traegt die Nummer, die bei seinem Absenden aktuell war; der Dienst
     * beendet sich nur, wenn seitdem kein neuer Lauf begonnen hat (Import endet, Frage beginnt sofort danach).
     */
    val generation = java.util.concurrent.atomic.AtomicInteger(0)

    private fun send(action: String, title: String, text: String) {
        val i = Intent(this, ResearchService::class.java)
            .setAction(action)
            .putExtra(ResearchService.EXTRA_GEN, generation.get())
            .putExtra(ResearchService.EXTRA_TITLE, title)
            .putExtra(ResearchService.EXTRA_TEXT, text)
        runCatching {
            if (action == ResearchService.ACTION_START) ContextCompat.startForegroundService(this, i)
            else if (ResearchService.running) startService(i)
            else if (action == ResearchService.ACTION_FINISH) ResearchService.notifyDone(this, title, text)
        }.onFailure { android.util.Log.w("VeritasService", "Dienst: ${it.message}") }
    }
}
