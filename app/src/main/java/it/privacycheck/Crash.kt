package it.privacycheck

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Rapporto di errore locale: salvato nel telefono, condiviso solo se lo decide l'utente. */
object Crash {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ver = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (x: Exception) {
                    "?"
                }
                val txt = "GhostALO $ver\n" + Date() + "\nAndroid " + Build.VERSION.RELEASE +
                    " (API " + Build.VERSION.SDK_INT + ")\n" + Build.MANUFACTURER + " " + Build.MODEL +
                    "\nThread: " + t.name + "\n\n" + sw
                file(app).writeText(txt.take(20000))
            } catch (x: Throwable) {
                // niente da fare
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? {
        return try {
            val f = file(ctx)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            // ignora
        }
    }
}
