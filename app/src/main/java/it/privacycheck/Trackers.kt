package it.privacycheck

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.zip.ZipFile

class AppTrackers(val pkg: String, val label: String, val trackers: Set<String>)

class TrackerResult(val analyzed: Int, val apps: List<AppTrackers>)

private class Node {
    val next = arrayOfNulls<Node>(128)
    var name: String? = null
}

/**
 * Cerca nel codice (file classes*.dex) delle app installate i nomi delle librerie
 * pubblicitarie e di tracciamento più diffuse. Funziona interamente offline.
 */
@Suppress("DEPRECATION")
object Trackers {

    private val list = listOf(
        "Google Analytics" to "com/google/android/gms/analytics/",
        "Google Tag Manager" to "com/google/android/gms/tagmanager/",
        "Firebase Analytics" to "com/google/firebase/analytics/",
        "Google AdMob / Ads" to "com/google/android/gms/ads/",
        "Firebase Crashlytics" to "com/google/firebase/crashlytics/",
        "Crashlytics (Fabric)" to "com/crashlytics/android/",
        "Facebook Ads" to "com/facebook/ads/",
        "Facebook Analytics" to "com/facebook/appevents/",
        "Facebook SDK" to "com/facebook/FacebookSdk",
        "Flurry" to "com/flurry/",
        "AppsFlyer" to "com/appsflyer/",
        "Adjust" to "com/adjust/sdk/",
        "Branch" to "io/branch/",
        "Mixpanel" to "com/mixpanel/",
        "Amplitude" to "com/amplitude/",
        "Segment" to "com/segment/analytics/",
        "OneSignal" to "com/onesignal/",
        "Unity Ads" to "com/unity3d/ads/",
        "AppLovin" to "com/applovin/",
        "ironSource" to "com/ironsource/",
        "Vungle" to "com/vungle/",
        "Chartboost" to "com/chartboost/",
        "InMobi" to "com/inmobi/",
        "MoPub" to "com/mopub/",
        "StartApp" to "com/startapp/",
        "Tapjoy" to "com/tapjoy/",
        "Pangle (TikTok Ads)" to "com/bytedance/sdk/openadsdk/",
        "Kochava" to "com/kochava/",
        "Singular" to "com/singular/sdk/",
        "Braze (Appboy)" to "com/appboy/",
        "Braze" to "com/braze/",
        "Urban Airship" to "com/urbanairship/",
        "MoEngage" to "com/moengage/",
        "CleverTap" to "com/clevertap/",
        "Yandex Metrica" to "com/yandex/metrica/",
        "myTracker (VK)" to "com/my/tracker/",
        "Smaato" to "com/smaato/",
        "Fyber" to "com/fyber/",
        "AdColony" to "com/adcolony/",
        "Criteo" to "com/criteo/",
        "Taboola" to "com/taboola/",
        "Outbrain" to "com/outbrain/",
        "comScore" to "com/comscore/",
        "Huawei Analytics" to "com/huawei/hms/analytics/",
        "Huawei Ads" to "com/huawei/hms/ads/",
        "Microsoft AppCenter" to "com/microsoft/appcenter/",
        "Bugsnag" to "com/bugsnag/",
        "Adobe Analytics" to "com/adobe/mobile/",
        "Tune" to "com/tune/",
        "Leanplum" to "com/leanplum/",
        "Optimizely" to "com/optimizely/"
    )

    private val root: Node = buildTrie()

    private fun buildTrie(): Node {
        val r = Node()
        for ((name, prefix) in list) {
            var n = r
            for (ch in prefix) {
                val c = ch.code
                if (c >= 128) continue
                var nx = n.next[c]
                if (nx == null) {
                    nx = Node()
                    n.next[c] = nx
                }
                n = nx
            }
            n.name = name
        }
        return r
    }

    private fun scanBytes(buf: ByteArray, found: MutableSet<String>) {
        val big = 'L'.code.toByte()
        val n = buf.size
        var i = 0
        while (i < n) {
            if (buf[i] == big) {
                var node = root
                var j = i + 1
                while (j < n) {
                    val b = buf[j].toInt()
                    if (b < 0) break
                    val nx = node.next[b] ?: break
                    node = nx
                    val nm = node.name
                    if (nm != null) {
                        found.add(nm)
                        break
                    }
                    j++
                }
            }
            i++
        }
    }

    private fun scanApk(path: String): Set<String> {
        val found = HashSet<String>()
        try {
            ZipFile(path).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val nm = e.name
                    if (nm.startsWith("classes") && nm.endsWith(".dex") && e.size in 1L..60000000L) {
                        val data = zf.getInputStream(e).use { it.readBytes() }
                        scanBytes(data, found)
                    }
                }
            }
        } catch (e: Exception) {
            // APK non leggibile
        }
        return found
    }

    fun scanAll(ctx: Context, progress: (Int, Int) -> Unit): TrackerResult {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0).filter {
            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != ctx.packageName
        }
        val out = ArrayList<AppTrackers>()
        var done = 0
        for (ai in apps) {
            Work.check()
            val found = scanApk(ai.sourceDir)
            out.add(AppTrackers(ai.packageName, pm.getApplicationLabel(ai).toString(), found))
            done++
            progress(done, apps.size)
        }
        return TrackerResult(apps.size, out)
    }
}
