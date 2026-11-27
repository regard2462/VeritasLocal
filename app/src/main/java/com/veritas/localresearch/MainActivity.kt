package com.veritas.localresearch

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.veritas.localresearch.ui.ResearchScreen
import com.veritas.localresearch.ui.ResearchViewModel
import com.veritas.localresearch.ui.VeritasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationsOnce()
        val vm: ResearchViewModel = (application as VeritasApp).researchViewModel
        setContent {
            VeritasTheme {
                ResearchScreen(viewModel = vm)
                // Nur in Debug-Fassungen: automatischer Testlauf per Intent (Frage, Modelldatei, Tiefe)
                val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val q = intent.getStringExtra("autorun_q")
                LaunchedEffect(q) {
                    if (debuggable && !q.isNullOrBlank()) {
                        intent.getStringExtra("autorun_ui")?.let { code -> vm.updateSettings { it.copy(uiLanguage = code) } }
                        vm.updateSettings { it.copy(answerWords = intent.getIntExtra("autorun_words", 0)) }
                        intent.getStringExtra("autorun_len")?.let { name ->
                            com.veritas.localresearch.settings.AnswerLength.entries.firstOrNull { it.name == name }
                                ?.let { len -> vm.updateSettings { it.copy(answerLength = len) } }
                        }
                        vm.autorun(
                            question = q,
                            modelFileName = intent.getStringExtra("autorun_model").orEmpty(),
                            depth = intent.getIntExtra("autorun_depth", 2),
                            tag = intent.getStringExtra("autorun_tag") ?: "run",
                            docPath = intent.getStringExtra("autorun_doc"),
                            sources = intent.getStringExtra("autorun_sources")
                        )
                    }
                }
            }
        }
    }

    /** Benachrichtigungen (Fortschritt im Hintergrund, Ergebnis) einmalig erfragen; ohne sie laeuft die Recherche trotzdem. */
    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || ResearchService.canNotify(this)) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }
}
