package it.privacycheck

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File

class ApkInfo(
    val file: File,
    val pkg: String?,
    val label: String,
    val readable: Boolean,
    val spy: Boolean,
    val installed: Boolean,
    val certDiffers: Boolean,
    val sensitive: List<String>,
    val permCount: Int
) {
    /** 0 = nessun problema evidente, 2 = attenzione, 3 = grave */
    val level: Int
        get() = when {
            spy -> 3
            certDiffers || sensitive.size >= 3 -> 2
            else -> 0
        }
}

/**
 * Controlla i file .apk presenti nel telefono PRIMA che vengano installati:
 * confronto con l'elenco di app spia, firma diversa da quella dell'app già installata,
 * permessi sensibili richiesti. Non installa e non elimina niente.
 */
@Suppress("DEPRECATION")
object ApkCheck {

    private val names = linkedMapOf(
        Manifest.permission.READ_SMS to "SMS",
        Manifest.permission.READ_CALL_LOG to tr("registro chiamate"),
        Manifest.permission.RECORD_AUDIO to "microfono",
        Manifest.permission.CAMERA to "fotocamera",
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to tr("posizione in background"),
        Manifest.permission.READ_CONTACTS to "contatti",
        "android.permission.SYSTEM_ALERT_WINDOW" to tr("sovrapposizione allo schermo"),
        "android.permission.REQUEST_INSTALL_PACKAGES" to tr("installare altre app")
    )

    fun scan(ctx: Context): List<ApkInfo> {
        val pm = ctx.packageManager
        val (spyP, spyC) = Stalker.load(ctx)
        val root = Environment.getExternalStorageDirectory()
        val apks = ArrayList<File>()
        val stack = ArrayList<File>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 300000 && apks.size < 200) {
            Work.check()
            val dir = stack.removeAt(stack.size - 1)
            val entries = dir.listFiles() ?: continue
            for (e in entries) {
                visited++
                if (e.isDirectory) {
                    if (dir == root && e.name == "Android") continue
                    stack.add(e)
                } else if (e.isFile && e.name.lowercase().endsWith(".apk")) {
                    apks.add(e)
                }
            }
        }

        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val out = ArrayList<ApkInfo>()
        for (f in apks) {
            Work.check()
            val pi: PackageInfo? = try {
                pm.getPackageArchiveInfo(f.absolutePath, flags)
            } catch (e: Exception) {
                null
            }
            if (pi == null) {
                out.add(ApkInfo(f, null, f.name, false, false, false, false, emptyList(), 0))
                continue
            }
            val ai: ApplicationInfo? = pi.applicationInfo
            var label = f.name
            if (ai != null) {
                ai.sourceDir = f.absolutePath
                ai.publicSourceDir = f.absolutePath
                try {
                    label = pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    // tieni il nome del file
                }
            }
            val certs = Stalker.certHashes(pi)
            val spy = pi.packageName in spyP || (spyC.isNotEmpty() && certs.any { it in spyC })

            var installed = false
            var differs = false
            try {
                val cur = pm.getPackageInfo(pi.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                installed = true
                val curCerts = Stalker.certHashes(cur)
                differs = certs.isNotEmpty() && curCerts.isNotEmpty() && certs.none { it in curCerts }
            } catch (e: Exception) {
                // non installata
            }

            val req = pi.requestedPermissions ?: emptyArray()
            val sens = names.filter { it.key in req }.values.toList()
            out.add(ApkInfo(f, pi.packageName, label, true, spy, installed, differs, sens, req.size))
        }
        return out.sortedWith(compareByDescending<ApkInfo> { it.level }.thenBy { it.label })
    }
}
