package it.privacycheck

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

data class MicApp(val pkg: String, val label: String, val lastUsed: Long, val system: Boolean)

/** Elenco delle app che hanno ricevuto il permesso di microfono o fotocamera. */
object MicCam {
    const val DAY = 24L * 60 * 60 * 1000

    /** @param usage true se l'accesso alle statistiche di utilizzo è concesso */
    fun scan(ctx: Context, perm: String, usage: Boolean): List<MicApp> {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
        val now = System.currentTimeMillis()
        val stats = if (usage) {
            try {
                (ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
                    .queryAndAggregateUsageStats(now - 30 * DAY, now)
            } catch (e: Exception) {
                null
            }
        } else null
        val out = ArrayList<MicApp>()
        for (p in pkgs) {
            if (p.packageName == ctx.packageName) continue
            val req = p.requestedPermissions ?: continue
            if (perm !in req) continue
            if (pm.checkPermission(perm, p.packageName) != PackageManager.PERMISSION_GRANTED) continue
            val ai = p.applicationInfo ?: continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && pm.getLaunchIntentForPackage(p.packageName) == null) continue
            val label = try {
                pm.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                p.packageName
            }
            val last = stats?.get(p.packageName)?.lastTimeUsed ?: 0L
            out.add(MicApp(p.packageName, label, last, system))
        }
        return out.sortedWith(compareByDescending<MicApp> { it.lastUsed }.thenBy { it.label.lowercase() })
    }
}
