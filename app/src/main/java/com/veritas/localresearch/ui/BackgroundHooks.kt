package com.veritas.localresearch.ui

/**
 * Meldet Beginn, Fortschritt und Ende eines Laufs an die Plattform. Android haelt damit einen Vordergrunddienst
 * mit Benachrichtigung, damit der Lauf weitergeht, wenn die App im Hintergrund liegt oder geschlossen wird.
 * Auf dem Desktop bleibt [hooks] leer.
 */
object BackgroundWork {
    interface Hooks {
        fun started(title: String)
        fun progress(title: String, detail: String)
        fun finished(title: String, text: String)
    }

    @Volatile var hooks: Hooks? = null
}
