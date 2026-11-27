package androidx.lifecycle

import android.app.Application

/** Desktop-Ersatz fuer AndroidViewModel; ViewModel selbst und viewModelScope kommen aus lifecycle-viewmodel. */
open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
}
