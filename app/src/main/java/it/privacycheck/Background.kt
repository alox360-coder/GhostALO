package it.privacycheck

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

class BgApp(
    val pkg: String,
    val label: String,
    val serviceMillis: Long,
    val starts: Int,
    val screenMillis: Long,
    val boot: Boolean
) {
    /** Tanto tempo in background e quasi mai aperta: la più sospetta di consumi inutili. */
    val wasteful: Boolean
        get() = serviceMillis >= 3600000L && screenMillis < 600000L
}

/**
 * Quali app hanno tenuto attivo un servizio in primo piano (quello con la notifica fissa)
 * e per quanto, rispetto a quanto sono state davvero usate. Dati dagli eventi di sistema:
 * Android non permette di misurare la batteria per app né di elencare i processi.
 */
object Background {

    fun scan(ctx: Context, hours: Int): List<BgApp>? {
        if (!Cleaner.hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager
        val end = System.currentTimeMillis()
        val begin = end - hours * 3600L * 1000L

        val starts = HashMap<String, Long>()
        val millis = HashMap<String, Long>()
        val count = HashMap<String, Int>()

        val events = usm.queryEvents(begin, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val key = pkg + "/" + (e.className ?: "")
            when (e.eventType) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                    if (!starts.containsKey(key)) {
                        starts[key] = e.timeStamp
                        count[pkg] = (count[pkg] ?: 0) + 1
                    }
                }
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                    val s = starts.remove(key) ?: begin
                    millis[pkg] = (millis[pkg] ?: 0L) + (e.timeStamp - s).coerceAtLeast(0L)
                }
            }
        }
        // servizi ancora attivi adesso
        for ((key, s) in starts) {
            val pkg = key.substringBefore('/')
            millis[pkg] = (millis[pkg] ?: 0L) + (end - s).coerceAtLeast(0L)
        }

        val screen = usm.queryAndAggregateUsageStats(begin, end)
        val out = ArrayList<BgApp>()
        for ((pkg, ms) in millis) {
            if (pkg == ctx.packageName || ms < 60000L) continue
            val ai = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (ex: Exception) {
                continue
            }
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val boot = try {
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                pi.requestedPermissions?.contains("android.permission.RECEIVE_BOOT_COMPLETED") == true
            } catch (ex: Exception) {
                false
            }
            out.add(
                BgApp(
                    pkg,
                    pm.getApplicationLabel(ai).toString(),
                    ms,
                    count[pkg] ?: 1,
                    screen[pkg]?.totalTimeInForeground ?: 0L,
                    boot
                )
            )
        }
        return out.sortedByDescending { it.serviceMillis }.take(20)
    }
}
