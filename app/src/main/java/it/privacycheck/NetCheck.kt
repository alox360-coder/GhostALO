package it.privacycheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/** Controllo della rete attuale: protezione del Wi-Fi, VPN, DNS privato. Non usa Internet. */
@Suppress("DEPRECATION")
object NetCheck {

    private fun securityName(t: Int): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT < 31) return null
        return when (t) {
            WifiInfo.SECURITY_TYPE_OPEN -> Pair(tr("APERTA (nessuna password, traffico non cifrato dalla rete)"), 3)
            WifiInfo.SECURITY_TYPE_WEP -> Pair(tr("WEP (vecchia e facilmente violabile)"), 3)
            WifiInfo.SECURITY_TYPE_PSK -> Pair(tr("WPA/WPA2 con password"), 0)
            WifiInfo.SECURITY_TYPE_SAE -> Pair(tr("WPA3 con password"), 0)
            WifiInfo.SECURITY_TYPE_OWE -> Pair(tr("Open cifrata (OWE): senza password ma cifrata"), 0)
            WifiInfo.SECURITY_TYPE_EAP,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> Pair(tr("Aziendale (WPA Enterprise)"), 0)
            else -> null
        }
    }

    fun run(ctx: Context): List<Check> {
        val out = ArrayList<Check>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        var wifi = false
        var mobile = false
        var vpn = false
        try {
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) wifi = true
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) mobile = true
            }
        } catch (e: Exception) {
            // ignora
        }

        var weak = false
        if (wifi) {
            var name: Pair<String, Int>? = null
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (Build.VERSION.SDK_INT >= 31) name = securityName(wm.connectionInfo.currentSecurityType)
            } catch (e: Exception) {
                // ignora
            }
            if (name == null) {
                out.add(
                    Check(
                        1, tr("Wi-Fi: protezione non leggibile"),
                        tr("Android non dice a questa app che tipo di protezione ha la rete. Controllalo nelle impostazioni Wi-Fi: accanto alla rete deve comparire il lucchetto."),
                        Settings.ACTION_WIFI_SETTINGS
                    )
                )
            } else {
                if (name.second >= 3) weak = true
                out.add(Check(name.second, "Wi-Fi: " + name.first, tr("Tocca per aprire le impostazioni Wi-Fi."), Settings.ACTION_WIFI_SETTINGS))
            }
        } else if (mobile) {
            out.add(Check(0, tr("Rete mobile"), tr("Stai usando i dati del telefono: più sicuri di un Wi-Fi sconosciuto."), null))
        } else {
            out.add(Check(1, tr("Nessuna rete"), tr("Il telefono non è collegato a Internet."), null))
        }

        out.add(
            if (vpn) {
                Check(0, tr("VPN attiva"), tr("Il traffico passa da una VPN."), Settings.ACTION_VPN_SETTINGS)
            } else {
                Check(1, tr("Nessuna VPN attiva"), tr("Tocca per le impostazioni VPN."), Settings.ACTION_VPN_SETTINGS)
            }
        )

        val dns = Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "opportunistic"
        out.add(
            if (dns == "off") {
                Check(
                    2, tr("DNS privato spento"),
                    tr("Le richieste dei nomi dei siti viaggiano in chiaro sulla rete. Attivalo in Rete e Internet."),
                    Settings.ACTION_WIRELESS_SETTINGS
                )
            } else {
                Check(0, tr("DNS privato"), trf("Modalità: %s.", dns), null)
            }
        )

        // Giudizio complessivo, in cima
        val verdict = when {
            wifi && weak && !vpn -> Check(
                3, tr("SCONSIGLIATO: banca, acquisti e password"),
                tr("Rete Wi-Fi debole e senza VPN: chi è sulla stessa rete può osservare il traffico. Usa i dati mobili o una VPN di un fornitore affidabile."),
                null
            )
            wifi && !vpn -> Check(
                1, tr("Se la rete non è tua, valuta una VPN"),
                tr("Una rete con password condivisa (bar, hotel, ufficio) è comunque usata da molti. I siti con HTTPS restano cifrati, ma chi gestisce la rete vede a quali ti colleghi."),
                null
            )
            wifi && weak && vpn -> Check(
                2, tr("Rete debole, ma la VPN ti protegge"),
                tr("Mantieni la VPN accesa finché sei su questa rete."),
                null
            )
            else -> Check(0, tr("Nessun problema evidente"), tr("Dalle impostazioni la rete attuale non mostra segnali di rischio."), null)
        }
        out.add(0, verdict)
        return out
    }
}
