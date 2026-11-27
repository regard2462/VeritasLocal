package com.veritas.localresearch

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.veritas.localresearch.ui.ResearchScreen
import com.veritas.localresearch.ui.ResearchViewModel
import com.veritas.localresearch.ui.VeritasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeritasTheme {
                val vm: ResearchViewModel = viewModel()
                ResearchScreen(viewModel = vm)
                // Nur in Debug-Fassungen: automatischer Testlauf per Intent (Frage, Modelldatei, Tiefe)
                val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val q = intent.getStringExtra("autorun_q")
                LaunchedEffect(q) {
                    if (debuggable && !q.isNullOrBlank()) {
                        intent.getStringExtra("autorun_len")?.let { name ->
                            com.veritas.localresearch.settings.AnswerLength.entries.firstOrNull { it.name == name }
                                ?.let { len -> vm.updateSettings { it.copy(answerLength = len) } }
                        }
                        vm.autorun(
                            question = q,
                            modelFileName = intent.getStringExtra("autorun_model").orEmpty(),
                            depth = intent.getIntExtra("autorun_depth", 2),
                            tag = intent.getStringExtra("autorun_tag") ?: "run"
                        )
                    }
                }
            }
        }
    }
}
