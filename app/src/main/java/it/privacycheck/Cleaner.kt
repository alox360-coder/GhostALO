package it.privacycheck

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.os.StatFs
import java.security.MessageDigest

class JunkCategory(val title: String, val hint: String, var checked: Boolean) {
    val files = ArrayList<File>()
    val dirs = ArrayList<File>()
    var bytes = 0L
    val count: Int get() = files.size + dirs.size
}

data class AppSize(val pkg: String, val label: String, val cache: Long, val total: Long)

class CacheResult(
    val ok: Boolean,
    val freeBefore: Long,
    val freeAfter: Long,
    val cacheBefore: Long?,
    val cacheAfter: Long?,
    val message: String
)

data class UnusedApp(val pkg: String, val label: String, val total: Long, val daysAgo: Int?)

private class Item(val dir: File, val depth: Int, val thumbs: Boolean, val hidden: Boolean)

@Suppress("DEPRECATION")
object Cleaner {

    private val tempExt = setOf("tmp", "temp", "log", "dmp", "chk")
    private const val DAY = 24L * 3600L * 1000L

    fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        if (mode == AppOpsManager.MODE_DEFAULT) {
            // Su alcuni telefoni il permesso risulta "predefinito": conta quello di sistema.
            return ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hash(f: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            FileInputStream(f).use { input ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Cerca file inutili nell'archivio condiviso. Non modifica nulla. */
    fun scanJunk(): List<JunkCategory> {
        val apk = JunkCategory(
            tr("Installer APK"),
            tr("File .apk già scaricati: dopo l'installazione non servono più."),
            true
        )
        val temp = JunkCategory(
            tr("File temporanei e di log"),
            tr("File .tmp, .temp, .log, .dmp, .chk lasciati da app e sistema."),
            true
        )
        val thumbs = JunkCategory(
            tr("Miniature in cache"),
            tr("Cartelle .thumbnails e file .thumbdata: Android le ricrea se servono."),
            true
        )
        val empty = JunkCategory(
            tr("Cartelle vuote"),
            tr("Cartelle senza alcun file (le cartelle principali come DCIM non vengono toccate)."),
            true
        )
        val dup = JunkCategory(
            tr("File duplicati"),
            tr("Copie identiche di foto, video e documenti: viene tenuto l'esemplare più vecchio."),
            false
        )

        val root = Environment.getExternalStorageDirectory()
        val candidates = ArrayList<File>()
        val stack = ArrayList<Item>()
        stack.add(Item(root, 0, false, false))
        var visited = 0

        while (stack.isNotEmpty() && visited < 300000) {
            Work.check()
            val cur = stack.removeAt(stack.size - 1)
            val entries = cur.dir.listFiles() ?: continue
            if (entries.isEmpty() && cur.depth >= 2) {
                empty.dirs.add(cur.dir)
                continue
            }
            for (e in entries) {
                visited++
                if (e.isDirectory) {
                    if (cur.depth == 0 && e.name == "Android") continue
                    val isThumbs = cur.thumbs || e.name.equals(".thumbnails", ignoreCase = true)
                    stack.add(Item(e, cur.depth + 1, isThumbs, cur.hidden || e.name.startsWith(".")))
                } else if (e.isFile) {
                    val len = e.length()
                    val lower = e.name.lowercase()
                    val ext = lower.substringAfterLast('.', "")
                    if (cur.thumbs || lower.startsWith(".thumbdata")) {
                        thumbs.files.add(e)
                        thumbs.bytes += len
                    } else if (ext == "apk") {
                        apk.files.add(e)
                        apk.bytes += len
                    } else if (ext in tempExt) {
                        temp.files.add(e)
                        temp.bytes += len
                    } else if (!cur.hidden && len >= 51200L) {
                        candidates.add(e)
                    }
                }
            }
        }

        // Duplicati: stessa dimensione, poi stesso contenuto (SHA-1)
        val bySize = candidates.groupBy { it.length() }
        for ((size, group) in bySize) {
            Work.check()
            if (group.size < 2 || size > 200L * 1024L * 1024L) continue
            val byHash = HashMap<String, MutableList<File>>()
            for (f in group) {
                Work.check()
                val h = hash(f) ?: continue
                byHash.getOrPut(h) { ArrayList() }.add(f)
            }
            for (same in byHash.values) {
                if (same.size < 2) continue
                val sorted = same.sortedBy { it.lastModified() }
                for (f in sorted.drop(1)) {
                    dup.files.add(f)
                    dup.bytes += size
                }
            }
        }

        return listOf(apk, temp, thumbs, empty, dup).filter { it.count > 0 }
    }

    /** Elimina i file delle categorie. Restituisce (byte liberati, file non eliminati). */
    fun delete(ctx: Context, cats: List<JunkCategory>): Pair<Long, Int> {
        var freed = 0L
        var failed = 0
        val paths = ArrayList<String>()
        for (c in cats) {
            for (f in c.files) {
                val len = f.length()
                if (f.delete()) {
                    freed += len
                    paths.add(f.absolutePath)
                } else {
                    failed++
                }
            }
            // File.delete() su una cartella non vuota fallisce: è voluto, è una protezione.
            for (d in c.dirs.sortedByDescending { it.absolutePath.length }) {
                d.delete()
            }
        }
        if (paths.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
            } catch (e: Exception) {
                // ignora
            }
        }
        return Pair(freed, failed)
    }

    /**
     * Chiede ad Android di fare spazio: per soddisfare la richiesta il sistema cancella
     * da solo le cache delle altre app (metodo ufficiale, StorageManager.allocateBytes).
     * Non svuota "tutto": libera quanto basta per la richiesta, e dipende dal telefono.
     */
    fun freeSystemCache(ctx: Context): CacheResult {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val dir = ctx.filesDir
        val usage = hasUsageAccess(ctx)
        fun others(): Long? = if (usage) {
            try {
                appSizes(ctx).filter { it.pkg != ctx.packageName }.sumOf { it.cache }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return try {
            val uuid = sm.getUuidForPath(dir)
            val cacheBefore = others()
            val freeBefore = StatFs(dir.path).availableBytes
            val extra = (sm.getAllocatableBytes(uuid) - freeBefore).coerceAtLeast(0L)
            if (extra < 1024L * 1024L) {
                return CacheResult(
                    true, freeBefore, freeBefore, cacheBefore, cacheBefore,
                    tr("Android non segnala cache da liberare in questo momento.")
                )
            }
            val request = if (cacheBefore != null) minOf(extra, cacheBefore) else extra / 2
            val f = File(dir, "spazio.tmp")
            try {
                FileOutputStream(f).use { out -> sm.allocateBytes(out.fd, freeBefore + request) }
            } finally {
                f.delete()
            }
            try {
                Thread.sleep(1500)
            } catch (e: InterruptedException) {
                // ignora
            }
            val freeAfter = StatFs(dir.path).availableBytes
            CacheResult(true, freeBefore, freeAfter, cacheBefore, others(), "")
        } catch (e: Exception) {
            CacheResult(false, 0L, 0L, null, null, tr("Il sistema ha rifiutato la richiesta."))
        }
    }

    /** Dimensioni e cache di ogni app. Richiede l'accesso alle statistiche di utilizzo. */
    fun appSizes(ctx: Context): List<AppSize> {
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val pm = ctx.packageManager
        val me = Process.myUserHandle()
        val out = ArrayList<AppSize>()
        for (ai in pm.getInstalledApplications(0)) {
            try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, me)
                out.add(
                    AppSize(
                        ai.packageName,
                        pm.getApplicationLabel(ai).toString(),
                        st.cacheBytes,
                        st.appBytes + st.dataBytes
                    )
                )
            } catch (e: Exception) {
                // app non misurabile
            }
        }
        return out
    }

    /** App non di sistema che non risultano aperte da oltre 60 giorni. */
    fun unusedApps(ctx: Context, sizes: List<AppSize>): List<UnusedApp> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(now - 120L * DAY, now)
        val pm = ctx.packageManager
        val limit = now - 60L * DAY
        val sizeMap = sizes.associateBy { it.pkg }
        val out = ArrayList<UnusedApp>()
        for (ai in pm.getInstalledApplications(0)) {
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (ai.packageName == ctx.packageName) continue
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
            val installed = try {
                pm.getPackageInfo(ai.packageName, 0).firstInstallTime
            } catch (e: Exception) {
                now
            }
            if (installed > limit) continue
            val last = stats[ai.packageName]?.lastTimeUsed ?: 0L
            if (last < limit) {
                val days = if (last <= 0L) null else ((now - last) / DAY).toInt()
                out.add(
                    UnusedApp(
                        ai.packageName,
                        pm.getApplicationLabel(ai).toString(),
                        sizeMap[ai.packageName]?.total ?: 0L,
                        days
                    )
                )
            }
        }
        return out.sortedByDescending { it.total }.take(12)
    }
}
