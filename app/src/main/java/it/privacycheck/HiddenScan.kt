package it.privacycheck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

class HiddenApp(
    val pkg: String,
    val label: String,
    val sideloaded: Boolean,
    val sensitive: List<String>,
    val special: List<String>,
    val spy: Boolean
) {
    val score: Int
        get() = (if (spy) 100 else 0) + special.size * 10 + sensitive.size * 2 + (if (sideloaded) 3 else 0)
}

class HiddenFolder(
    val dir: File,
    val reason: String,
    var count: Int,
    var bytes: Long,
    val sample: ArrayList<File>
)

class HiddenReport(
    val apps: List<HiddenApp>,
    val normalCount: Int,
    val folders: List<HiddenFolder>?,
    val truncated: Boolean
)

/** App senza icona nel menu e cartelle nascoste con foto o video. Non modifica nulla. */
@Suppress("DEPRECATION")
object HiddenScan {

    private val media = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "mp4", "mkv", "3gp", "mov", "avi", "webm"
    )

    private val permNames = linkedMapOf(
        Manifest.permission.READ_SMS to "SMS",
        Manifest.permission.READ_CALL_LOG to tr("registro chiamate"),
        Manifest.permission.CAMERA to "fotocamera",
        Manifest.permission.RECORD_AUDIO to "microfono",
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to tr("posizione in background"),
        Manifest.permission.READ_CONTACTS to "contatti"
    )

    private fun granted(p: PackageInfo): Set<String> {
        val req = p.requestedPermissions ?: return emptySet()
        val fl = p.requestedPermissionsFlags ?: return emptySet()
        val out = HashSet<String>()
        for (i in req.indices) {
            if (i < fl.size && (fl[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) out.add(req[i])
        }
        return out
    }

    fun scan(ctx: Context, withFolders: Boolean): HiddenReport {
        val pm = ctx.packageManager
        val snap = Watch.snapshot(ctx)
        val flags = PackageManager.GET_PERMISSIONS
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }

        val apps = ArrayList<HiddenApp>()
        var normal = 0
        for (p in pkgs) {
            Work.check()
            val ai = p.applicationInfo ?: continue
            val pkg = p.packageName
            if (pkg == ctx.packageName) continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            if (pm.queryIntentActivities(launcher, 0).isNotEmpty()) continue

            val g = granted(p)
            val sens = permNames.filter { it.key in g }.values.toList()
            val special = ArrayList<String>()
            if (pkg in snap.acc) special.add(tr("accessibilità attiva"))
            if (pkg in snap.admin) special.add(tr("amministratore del dispositivo"))
            if (pkg in snap.listeners) special.add(tr("legge le notifiche"))
            val side = pkg in snap.side
            val spy = pkg in snap.spy
            if (spy || special.isNotEmpty() || side || sens.size >= 2) {
                apps.add(HiddenApp(pkg, pm.getApplicationLabel(ai).toString(), side, sens, special, spy))
            } else {
                normal++
            }
        }
        apps.sortByDescending { it.score }

        if (!withFolders) return HiddenReport(apps, normal, null, false)

        val root = Environment.getExternalStorageDirectory()
        val found = LinkedHashMap<String, HiddenFolder>()
        val stack = ArrayList<Triple<File, HiddenFolder?, Boolean>>()
        stack.add(Triple(root, null, true))
        var visited = 0
        var truncated = false
        while (stack.isNotEmpty()) {
            if (visited >= 400000) {
                truncated = true
                break
            }
            Work.check()
            val (dir, inherited, isRoot) = stack.removeAt(stack.size - 1)
            val entries = dir.listFiles() ?: continue
            var anchor = inherited
            if (!isRoot && anchor == null) {
                val lower = dir.name.lowercase()
                if (!lower.contains("thumb") && !lower.contains("cache")) {
                    if (dir.name.startsWith(".")) {
                        anchor = HiddenFolder(dir, tr("cartella nascosta (il nome inizia con un punto)"), 0, 0L, ArrayList())
                    } else if (entries.any { it.name == ".nomedia" }) {
                        anchor = HiddenFolder(dir, tr("esclusa dalla galleria (.nomedia)"), 0, 0L, ArrayList())
                    }
                }
            }
            val a = anchor
            for (e in entries) {
                visited++
                if (e.isDirectory) {
                    if (isRoot && e.name == "Android") continue
                    val lower = e.name.lowercase()
                    if (lower.contains("thumb") || lower.contains("cache")) continue
                    stack.add(Triple(e, a, false))
                } else if (a != null && e.isFile) {
                    val len = e.length()
                    if (len >= 30L * 1024L && e.name.lowercase().substringAfterLast('.', "") in media) {
                        val f = found.getOrPut(a.dir.absolutePath) { a }
                        f.count++
                        f.bytes += len
                        if (f.sample.size < 2) f.sample.add(e)
                    }
                }
            }
        }
        val folders = found.values.filter { it.count >= 3 }.sortedByDescending { it.bytes }.take(15)
        return HiddenReport(apps, normal, folders, truncated)
    }
}
