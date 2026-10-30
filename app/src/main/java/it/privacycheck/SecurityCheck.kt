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
            out.add(Check(0, "Blocco schermo", "PIN, password o sequenza attivi.", null))
        } else {
            penalty += 25
            out.add(
                Check(
                    3, "Blocco schermo ASSENTE",
                    "Chiunque abbia il telefono in mano può leggere tutto. Imposta un PIN.",
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
                    2, "Sistema operativo datato",
                    ver + ": non riceve più aggiornamenti di sicurezza dal produttore.",
                    Settings.ACTION_DEVICE_INFO_SETTINGS
                )
            )
        } else {
            out.add(Check(0, "Versione di Android", ver, null))
        }

        // Patch di sicurezza
        try {
            val patch = Build.VERSION.SECURITY_PATCH
            val days = ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now())
            val d = "Patch del $patch ($days giorni fa)."
            if (days <= 120) {
                out.add(Check(0, "Aggiornamento di sicurezza", d, null))
            } else if (days <= 240) {
                penalty += 10
                out.add(
                    Check(
                        2, "Aggiornamento di sicurezza non recente", d + " Cerca aggiornamenti.",
                        "android.settings.SYSTEM_UPDATE_SETTINGS"
                    )
                )
            } else {
                penalty += 20
                out.add(
                    Check(
                        3, "Aggiornamento di sicurezza MOLTO vecchio", d + " Aggiorna il sistema.",
                        "android.settings.SYSTEM_UPDATE_SETTINGS"
                    )
                )
            }
        } catch (e: Exception) {
            out.add(Check(1, "Aggiornamento di sicurezza", "Data non leggibile.", null))
        }

        // Crittografia
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val st = dpm.storageEncryptionStatus
            if (st == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
                st == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
                st == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
            ) {
                out.add(Check(0, "Crittografia dei dati", "Memoria cifrata.", null))
            } else {
                penalty += 15
                out.add(
                    Check(
                        3, "Memoria NON cifrata", "I dati sono leggibili se il telefono viene smontato.",
                        Settings.ACTION_SECURITY_SETTINGS
                    )
                )
            }
        } catch (e: Exception) {
            out.add(Check(1, "Crittografia dei dati", "Stato non leggibile.", null))
        }

        // Debug USB e opzioni sviluppatore
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        if (adb) {
            penalty += 10
            out.add(
                Check(
                    2, "Debug USB attivo",
                    "Un computer collegato col cavo potrebbe accedere al telefono. Disattivalo se non lo usi.",
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
            )
        } else if (dev) {
            penalty += 3
            out.add(
                Check(
                    1, "Opzioni sviluppatore attive", "Il debug USB è spento. Puoi disattivarle.",
                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
            )
        } else {
            out.add(Check(0, "Debug USB", "Spento.", null))
        }

        // DNS privato
        val dns = Settings.Global.getString(cr, "private_dns_mode") ?: "opportunistic"
        if (dns == "off") {
            penalty += 5
            out.add(
                Check(
                    2, "DNS privato disattivato",
                    "Le richieste DNS viaggiano in chiaro. Attivalo in Rete e Internet → DNS privato.",
                    Settings.ACTION_WIRELESS_SETTINGS
                )
            )
        } else {
            out.add(Check(0, "DNS privato", "Modalità: $dns.", null))
        }

        // VPN
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            out.add(Check(1, "VPN", if (vpn) "Una VPN è attiva ora." else "Nessuna VPN attiva ora.", null))
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
                    3, "Segnali di ROOT",
                    "Trovati $rootSigns indizi di accesso root o firmware non ufficiale: la sicurezza di Android è indebolita.",
                    null
                )
            )
        } else {
            out.add(Check(0, "Root", "Nessun indizio di root.", null))
        }

        // Timeout schermo
        val to = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60000)
        if (to > 120000) {
            penalty += 3
            out.add(
                Check(
                    2, "Spegnimento schermo lento", "Lo schermo resta acceso " + (to / 1000) + " secondi. Meglio 30-60.",
                    Settings.ACTION_DISPLAY_SETTINGS
                )
            )
        } else {
            out.add(Check(0, "Spegnimento schermo", "" + (to / 1000) + " secondi.", null))
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
                        2, "Accessibilità: $third app non di sistema",
                        names.joinToString(", ") + ". Possono leggere lo schermo: verifica che le riconosci.",
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                )
            } else {
                out.add(Check(0, "Servizi di accessibilità", "Nessuna app di terze parti.", null))
            }
        } catch (e: Exception) {
            // ignora
        }

        val score = if (penalty >= 100) 0 else 100 - penalty
        return SecurityResult(score, out)
    }
}
