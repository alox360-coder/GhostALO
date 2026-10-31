package it.privacycheck

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** level: 0 = ok, 1 = info, 2 = attenzione, 3 = problema grave */
class Check(val level: Int, val title: String, val detail: String, val action: String?)

class SecurityResult(val score: Int, val checks: List<Check>)

@Suppress("DEPRECATION")
object SecurityCheck {

    private val rootPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/data/local/bin/su",
        "/data/local/xbin/su", "/system/app/Superuser.apk"
    )

    private val rootPackages = listOf(
        "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.noshufou.android.su",
        "com.koushikdutta.superuser", "com.thirdparty.superuser"
    )

    fun run(ctx: Context): SecurityResult {
        val out = ArrayList<Check>()
        var penalty = 0
        val cr = ctx.contentResolver

        // Blocco schermo
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isDeviceSecure) {
            out.add(Check(0, tr("Blocco schermo"), tr("PIN, password o sequenza attivi."), null))
        } else {
            penalty += 25
            out.add(
                Check(
                    3, tr("Blocco schermo ASSENTE"),
                    tr("Chiunque abbia il telefono in mano può leggere tutto. Imposta un PIN."),
                    Settings.ACTION_SECURITY_SETTINGS
                )
            )
        }

        // Versione Android
        val ver = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        if (Build.VERSION.SDK_INT < 31) {
            penalty += 10
            out.add(
                Check(
                    2, tr("Sistema operativo datato"),
                    ver + tr(": non riceve più aggiornamenti di sicurezza dal produttore."),
                    Settings.ACTION_DEVICE_INFO_SETTINGS
                )
            )
        } else {
            out.add(Check(0, tr("Versione di Android"), ver, null))
        }

        // Patch di sicurezza
        try {
            val patch = Build.VERSION.SECURITY_PATCH
            val days = ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now())
            val d = trf("Patch del %s (%s giorni fa).", patch, days)
            if (days <= 120) {
                out.add(Check(0, tr("Aggiornamento di sicurezza"), d, null))
            } else if (days <= 240) {
                penalty += 10
                out.add(
                    Check(
                        2, tr("Aggiornamento di sicurezza non recente"), d + tr(" Cerca aggiornamenti."),
                        "android.settings.SYSTEM_UPDATE_SETTINGS"
                    )
                )
            } else {
                penalty += 20
                out.add(
                    Check(
                        3, tr("Aggiornamento di sicurezza MOLTO vecchio"), d + tr(" Aggiorna il sistema."),
                        "android.settings.SYSTEM_UPDATE_SETTINGS"
                    )
                )
            }
        } catch (e: Exception) {
            out.add(Check(1, tr("Aggiornamento di sicurezza"), tr("Data non leggibile."), null))
        }

        // Crittografia
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val st = dpm.storageEncryptionStatus
            if (st == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
                st == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
                st == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
            ) {
                out.add(Check(0, tr("Crittografia dei dati"), tr("Memoria cifrata."), null))
            } else {
                penalty += 15
                out.add(
                    Check(
                        3, tr("Memoria NON cifrata"), tr("I dati sono leggibili se il telefono viene smontato."),
                        Settings.ACTION_SECURITY_SETTINGS
                    )
                )
            }
        } catch (e: Exception) {
            out.add(Check(1, tr("Crittografia dei dati"), tr("Stato non leggibile."), null))
        }

        // Debug USB e opzioni sviluppatore
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        if (adb) {
            penalty += 10
            out.add(
                Check(
                    2, tr("Debug USB attivo"),
                    tr("Un computer collegato col cavo potrebbe accedere al telefono. Disattivalo se non lo usi."),
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
            )
        } else if (dev) {
            penalty += 3
            out.add(
                Check(
                    1, tr("Opzioni sviluppatore attive"), tr("Il debug USB è spento. Puoi disattivarle."),
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
            )
        } else {
            out.add(Check(0, tr("Debug USB"), tr("Spento."), null))
        }

        // DNS privato
        val dns = Settings.Global.getString(cr, "private_dns_mode") ?: "opportunistic"
        if (dns == "off") {
            penalty += 5
            out.add(
                Check(
                    2, tr("DNS privato disattivato"),
                    tr("Le richieste DNS viaggiano in chiaro. Attivalo in Rete e Internet → DNS privato."),
                    Settings.ACTION_WIRELESS_SETTINGS
                )
            )
        } else {
            out.add(Check(0, tr("DNS privato"), trf("Modalità: %s.", dns), null))
        }

        // VPN
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (vpn) {
                out.add(
                    Check(
                        0, tr("VPN attiva"),
                        tr("Il traffico passa da una VPN. Tocca per aprirne le impostazioni."),
                        Settings.ACTION_VPN_SETTINGS
                    )
                )
            } else {
                out.add(
                    Check(
                        1, tr("Nessuna VPN attiva"),
                        tr("Non è obbligatoria, quindi non toglie punti. Su Wi-Fi pubblici ne conviene una di un fornitore affidabile. Tocca per le impostazioni VPN."),
                        Settings.ACTION_VPN_SETTINGS
                    )
                )
            }
        } catch (e: Exception) {
            // ignora
        }

        // Root
        var rootSigns = 0
        for (p in rootPaths) if (File(p).exists()) rootSigns++
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) rootSigns++
        for (p in rootPackages) {
            try {
                ctx.packageManager.getPackageInfo(p, 0)
                rootSigns++
            } catch (e: Exception) {
                // non installato
            }
        }
        if (rootSigns > 0) {
            penalty += 25
            out.add(
                Check(
                    3, tr("Segnali di ROOT"),
                    trf("Trovati %s indizi di accesso root o firmware non ufficiale: la sicurezza di Android è indebolita.", rootSigns),
                    null
                )
            )
        } else {
            out.add(Check(0, "Root", tr("Nessun indizio di root."), null))
        }

        // Timeout schermo
        val to = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
        if (to > 120000) {
            penalty += 3
            out.add(
                Check(
                    2, tr("Spegnimento schermo lento"), tr("Lo schermo resta acceso ") + (to / 1000) + tr(" secondi. Meglio 30-60."),
                    Settings.ACTION_DISPLAY_SETTINGS
                )
            )
        } else {
            out.add(Check(0, tr("Spegnimento schermo"), "" + (to / 1000) + tr(" secondi."), null))
        }

        // Accessibilità di terze parti
        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            var third = 0
            val names = ArrayList<String>()
            for (s in am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                val pkg = s.resolveInfo?.serviceInfo?.packageName ?: continue
                try {
                    val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
                    if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                        third++
                        names.add(ctx.packageManager.getApplicationLabel(ai).toString())
                    }
                } catch (e: Exception) {
                    // ignora
                }
            }
            if (third > 0) {
                penalty += 5
                out.add(
                    Check(
                        2, trf("Accessibilità: %s app non di sistema", third),
                        names.joinToString(", ") + tr(". Possono leggere lo schermo: verifica che le riconosci."),
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                )
            } else {
                out.add(Check(0, tr("Servizi di accessibilità"), tr("Nessuna app di terze parti."), null))
            }
        } catch (e: Exception) {
            // ignora
        }

        val score = if (penalty >= 100) 0 else 100 - penalty
        return SecurityResult(score, out)
    }
}
