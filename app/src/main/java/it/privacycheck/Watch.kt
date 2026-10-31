package it.privacycheck

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

class Alert(
    val id: Int,
    val high: Boolean,
    val title: String,
    val text: String,
    val pkg: String?
)

class Snap(
    val installed: Set<String>,
    val side: Set<String>,
    val risk: Set<String>,
    val acc: Set<String>,
    val admin: Set<String>,
    val listeners: Set<String>,
    val spy: Set<String>,
    val labels: Map<String, String>
)

/**
 * Controllo periodico in background. Confronta lo stato attuale con quello salvato
 * l'ultima volta e manda una notifica per ogni novità sospetta. Tutto resta nel telefono.
 */
@Suppress("DEPRECATION")
object Watch {

    const val JOB_ID = 4711
    private const val PERIOD_MS = 30L * 60L * 1000L
    private const val CH_HIGH = "alert_high"
    private const val CH_INFO = "alert_info"
    private const val MAX_NOTIFICATIONS = 5

    val stores = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.huawei.appmarket",
        "com.xiaomi.mipicks",
        "com.amazon.venezia",
        "com.heytap.market",
        "com.oppo.market",
        "com.vivo.appstore",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    // ---------- Impostazioni ----------

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun alertNewApk(ctx: Context): Boolean = prefs(ctx).getBoolean("alert_new_apk", true)

    fun setAlertNewApk(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("alert_new_apk", v).apply()
    }

    private fun getSet(pr: SharedPreferences, key: String): Set<String> =
        pr.getStringSet(key, null)?.toSet() ?: emptySet()

    // ---------- Pianificazione ----------

    private fun scheduler(ctx: Context): JobScheduler =
        ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    fun isScheduled(ctx: Context): Boolean = scheduler(ctx).getPendingJob(JOB_ID) != null

    private fun schedule(ctx: Context) {
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchJobService::class.java))
            .setPeriodic(PERIOD_MS)
            .setPersisted(true)
            .build()
        scheduler(ctx).schedule(info)
    }

    fun enable(ctx: Context) {
        prefs(ctx).edit().putBoolean("enabled", true).putBoolean("init", false).apply()
        schedule(ctx)
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().putBoolean("enabled", false).apply()
        scheduler(ctx).cancel(JOB_ID)
    }

    /** Se gli avvisi sono attivi ma il job è sparito (es. dopo un arresto forzato), lo riprogramma. */
    fun ensureScheduled(ctx: Context) {
        try {
            if (isEnabled(ctx) && !isScheduled(ctx)) schedule(ctx)
        } catch (e: Exception) {
            // ignora
        }
    }

    // ---------- Fotografia dello stato ----------

    private fun thirdParty(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return false
        return try {
            (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0
        } catch (e: Exception) {
            true
        }
    }

    private fun labelOf(ctx: Context, pkg: String): String {
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun granted(p: PackageInfo): Set<String> {
        val req = p.requestedPermissions ?: return emptySet()
        val fl = p.requestedPermissionsFlags ?: return emptySet()
        val out = HashSet<String>()
        for (i in req.indices) {
            if (i < fl.size && (fl[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) out.add(req[i])
        }
        return out
    }

    fun snapshot(ctx: Context): Snap {
        val pm = ctx.packageManager
        val (spyP, spyC) = Stalker.load(ctx)
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }

        val installed = HashSet<String>()
        val side = HashSet<String>()
        val risk = HashSet<String>()
        val spy = HashSet<String>()
        val labels = HashMap<String, String>()

        for (p in pkgs) {
            val ai = p.applicationInfo ?: continue
            val pkg = p.packageName
            if (pkg == ctx.packageName) continue
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isSpy = pkg in spyP ||
                (!sys && spyC.isNotEmpty() && Stalker.certHashes(p).any { it in spyC })
            if (isSpy) {
                spy.add(pkg)
                labels[pkg] = pm.getApplicationLabel(ai).toString()
            }
            if (sys) continue
            installed.add(pkg)
            labels[pkg] = pm.getApplicationLabel(ai).toString()
            val installer = try {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } catch (e: Exception) {
                null
            }
            if (installer == null || installer !in stores) {
                side.add(pkg)
                val g = granted(p)
                val sensitive = Manifest.permission.READ_SMS in g ||
                    Manifest.permission.READ_CALL_LOG in g ||
                    (Manifest.permission.CAMERA in g && Manifest.permission.RECORD_AUDIO in g)
                if (sensitive) risk.add(pkg)
            }
        }

        val acc = HashSet<String>()
        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            for (s in am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                val pk = s.resolveInfo?.serviceInfo?.packageName ?: continue
                if (thirdParty(ctx, pk)) {
                    acc.add(pk)
                    labels[pk] = labelOf(ctx, pk)
                }
            }
        } catch (e: Exception) {
            // ignora
        }

        val admin = HashSet<String>()
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admins = dpm.activeAdmins
            if (admins != null) {
                for (c in admins) {
                    if (thirdParty(ctx, c.packageName)) {
                        admin.add(c.packageName)
                        labels[c.packageName] = labelOf(ctx, c.packageName)
                    }
                }
            }
        } catch (e: Exception) {
            // ignora
        }

        val listeners = HashSet<String>()
        try {
            val nl = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            for (part in nl.split(":")) {
                val pk = ComponentName.unflattenFromString(part)?.packageName ?: continue
                if (thirdParty(ctx, pk)) {
                    listeners.add(pk)
                    labels[pk] = labelOf(ctx, pk)
                }
            }
        } catch (e: Exception) {
            // ignora
        }

        return Snap(installed, side, risk, acc, admin, listeners, spy, labels)
    }

    private fun saveStatus(ctx: Context, cur: Snap) {
        prefs(ctx).edit()
            .putLong("st_time", System.currentTimeMillis())
            .putInt("st_spy", cur.spy.size)
            .putInt("st_acc", cur.acc.size)
            .putInt("st_adm", cur.admin.size)
            .putInt("st_lst", cur.listeners.size)
            .putInt("st_risk", cur.risk.size)
            .apply()
        StatusWidget.updateAll(ctx)
    }

    // ---------- Controlli ----------

    /** Aggiorna solo lo stato mostrato (widget e schermata), senza avvisi né confronti. */
    @Synchronized
    fun refreshStatus(ctx: Context) {
        saveStatus(ctx, snapshot(ctx))
    }

    @Synchronized
    fun refreshAndCheck(ctx: Context) {
        if (isEnabled(ctx)) runCheck(ctx) else refreshStatus(ctx)
    }

    /** Controllo completo: confronta con l'ultimo stato salvato e manda le notifiche. */
    @Synchronized
    fun runCheck(ctx: Context) {
        if (!isEnabled(ctx)) return
        val cur = snapshot(ctx)
        val pr = prefs(ctx)
        val init = pr.getBoolean("init", false)
        val alerts = ArrayList<Alert>()
        fun lab(p: String): String = cur.labels[p] ?: p

        // App spia note: si avvisa sempre, anche al primo controllo.
        val bSpy = getSet(pr, "b_spy")
        for (p in cur.spy) {
            if (p !in bSpy) {
                alerts.add(
                    Alert(
                        ("spy:" + p).hashCode(), true, tr("APP SPIA NOTA"),
                        lab(p) + tr(" è nell'elenco pubblico di stalkerware. Tocca per aprirla e disinstallarla."),
                        p
                    )
                )
            }
        }

        // Novità: solo dopo che è stato salvato il primo stato di riferimento.
        if (init) {
            val bAcc = getSet(pr, "b_acc")
            val bAdm = getSet(pr, "b_adm")
            val bLst = getSet(pr, "b_lst")
            val bRisk = getSet(pr, "b_risk")
            val bSide = getSet(pr, "b_side")

            for (p in cur.acc) {
                if (p !in bAcc) {
                    alerts.add(
                        Alert(
                            ("acc:" + p).hashCode(), true, tr("Nuovo servizio di accessibilità"),
                            lab(p) + tr(" può ora leggere lo schermo e ciò che scrivi. Se non l'hai attivato tu, disattivalo."),
                            p
                        )
                    )
                }
            }
            for (p in cur.admin) {
                if (p !in bAdm) {
                    alerts.add(
                        Alert(
                            ("adm:" + p).hashCode(), true, tr("Nuovo amministratore del dispositivo"),
                            lab(p) + tr(" è diventata amministratore del telefono e sarà difficile da rimuovere."),
                            p
                        )
                    )
                }
            }
            for (p in cur.risk) {
                if (p !in bRisk) {
                    alerts.add(
                        Alert(
                            ("risk:" + p).hashCode(), true, tr("App da APK con permessi sensibili"),
                            lab(p) + tr(" (installata fuori dai negozi) può leggere SMS o chiamate, oppure usare fotocamera e microfono."),
                            p
                        )
                    )
                }
            }
            for (p in cur.listeners) {
                if (p !in bLst) {
                    alerts.add(
                        Alert(
                            ("lst:" + p).hashCode(), false, tr("Nuovo lettore di notifiche"),
                            lab(p) + tr(" può ora leggere tutte le tue notifiche."),
                            p
                        )
                    )
                }
            }
            if (alertNewApk(ctx)) {
                for (p in cur.side) {
                    if (p !in bSide && p !in cur.spy && p !in cur.risk && p !in cur.acc && p !in cur.admin) {
                        alerts.add(
                            Alert(
                                ("apk:" + p).hashCode(), false, tr("Nuova app installata da APK"),
                                lab(p) + tr(" non arriva da un negozio ufficiale. Se non l'hai installata tu, controllala."),
                                p
                            )
                        )
                    }
                }
            }
        }

        // Si salva lo stato PRIMA di notificare, così lo stesso avviso non si ripete.
        pr.edit()
            .putStringSet("b_inst", HashSet(cur.installed))
            .putStringSet("b_side", HashSet(cur.side))
            .putStringSet("b_risk", HashSet(cur.risk))
            .putStringSet("b_acc", HashSet(cur.acc))
            .putStringSet("b_adm", HashSet(cur.admin))
            .putStringSet("b_lst", HashSet(cur.listeners))
            .putStringSet("b_spy", HashSet(cur.spy))
            .putBoolean("init", true)
            .apply()
        saveStatus(ctx, cur)

        if (alerts.isNotEmpty()) {
            val sorted = alerts.sortedByDescending { it.high }
            for (a in sorted.take(MAX_NOTIFICATIONS)) post(ctx, a)
            if (sorted.size > MAX_NOTIFICATIONS) {
                post(
                    ctx,
                    Alert(
                        8888, false, tr("Altri avvisi"),
                        tr("… e altri ") + (sorted.size - MAX_NOTIFICATIONS) + tr(" avvisi. Apri l'app per controllare."),
                        null
                    )
                )
            }
        }
    }

    // ---------- Notifiche ----------

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val high = NotificationChannel(CH_HIGH, tr("Avvisi importanti"), NotificationManager.IMPORTANCE_HIGH)
        high.description = tr("App spia, nuovi servizi di accessibilità e amministratori del dispositivo")
        nm.createNotificationChannel(high)
        val info = NotificationChannel(CH_INFO, tr("Altri avvisi"), NotificationManager.IMPORTANCE_DEFAULT)
        info.description = tr("Nuovi lettori di notifiche e nuove app installate da APK")
        nm.createNotificationChannel(info)
    }

    private fun post(ctx: Context, a: Alert) {
        try {
            ensureChannels(ctx)
            val intent = if (a.pkg != null) {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
            } else {
                Intent(ctx, MainActivity::class.java)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                ctx, a.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, if (a.high) CH_HIGH else CH_INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(a.title)
                .setContentText(a.text)
                .setStyle(Notification.BigTextStyle().bigText(a.text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(a.id, n)
        } catch (e: Exception) {
            // notifiche non consentite: niente da fare
        }
    }

    fun sendTest(ctx: Context) {
        post(
            ctx,
            Alert(
                9999, false, tr("Avviso di prova"),
                tr("Se vedi questo messaggio, le notifiche funzionano."), null
            )
        )
    }
}

class WatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Thread {
            try {
                Watch.runCheck(ctx)
            } catch (e: Exception) {
                // riproverà al prossimo giro
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
