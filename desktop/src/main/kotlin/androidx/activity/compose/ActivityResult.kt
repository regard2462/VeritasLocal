package androidx.activity.compose

import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Desktop-Ersatz: der Vertrag oeffnet einen Dateidialog und liefert das Ergebnis an [onResult]. */
abstract class ActivityResultLauncher<I> {
    abstract fun launch(input: I)
}

@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> {
    val current = rememberUpdatedState(onResult)
    return remember(contract) {
        object : ActivityResultLauncher<I>() {
            override fun launch(input: I) {
                current.value(contract.run(input))
            }
        }
    }
}
