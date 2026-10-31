package it.privacycheck

import java.util.concurrent.CancellationException

/** Permette di fermare una scansione quando l'utente esce dalla schermata. */
object Work {
    fun check() {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
    }
}
