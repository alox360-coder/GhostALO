package it.privacycheck

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Blocco dell'app con il blocco schermo del telefono (PIN, sequenza, impronta).
 * Non salva nessun PIN: usa la schermata di sistema. Dopo l'uscita dall'app il blocco
 * scatta solo quando è passato il tempo scelto; all'avvio a freddo scatta sempre.
 */
object Lock {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)

    // Resta in memoria: se il processo viene chiuso, al prossimo avvio si chiede lo sblocco.
    private var lastActive = -1L

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()
    fun graceMin(ctx: Context) = prefs(ctx).getInt("grace", 5)
    fun setGraceMin(ctx: Context, m: Int) = prefs(ctx).edit().putInt("grace", m).apply()

    private fun km(ctx: Context) = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    /** True se il telefono ha un blocco schermo configurato. */
    fun available(ctx: Context) = km(ctx).isDeviceSecure

    fun needed(ctx: Context): Boolean {
        if (!enabled(ctx) || !available(ctx)) return false
        if (lastActive < 0) return true
        return SystemClock.elapsedRealtime() - lastActive > graceMin(ctx) * 60_000L
    }

    fun touch() {
        lastActive = SystemClock.elapsedRealtime()
    }

    fun intent(ctx: Context): Intent? =
        km(ctx).createConfirmDeviceCredentialIntent(tr("GhostALO"), tr("Sblocca l'app"))
}
