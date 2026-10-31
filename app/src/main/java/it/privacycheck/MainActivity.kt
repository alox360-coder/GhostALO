package it.privacycheck

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.Date

data class AppFinding(
    val pkg: String,
    val label: String,
    val score: Int,
    val reasons: List<String>
)

data class Report(
    val flagged: List<AppFinding>,
    val quietCount: Int,
    val camApps: List<String>,
    val micApps: List<String>,
    val accLines: List<String>,
    val keyboardLines: List<String>,
    val photoLines: List<String>?,
    val bigFiles: List<String>?,
    val storageLine: String,
    val spyHits: List<String>,
    val stalkerDbSize: Int
)

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private lateinit var box: LinearLayout
    private var screen = "intro"
    private var group: String? = null
    private var worker: Thread? = null

    /** Avvia un lavoro in secondo piano; il precedente viene fermato. */
    private fun work(block: () -> Unit) {
        worker?.interrupt()
        val t = Thread {
            try {
                block()
            } catch (e: java.util.concurrent.CancellationException) {
                // scansione annullata dall'utente
            }
        }
        worker = t
        t.start()
    }
    private var token = 0

    // Palette "terminale"
    private val red = Color.parseColor("#FF3B30")
    private val orange = Color.parseColor("#FFB000")
    private val green = Color.parseColor("#00FF41")
    private val dark = Color.parseColor("#00E63B")
    private val grey = Color.parseColor("#00A329")

    private val storeInstallers = setOf(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(28), dp(16), dp(28))
        scroll.addView(box)
        setContentView(scroll)
        Watch.ensureScheduled(this)
        val app0 = applicationContext
        Thread {
            try {
                Trash.purge(app0, false)
            } catch (e: Exception) {
                // ignora
            }
        }.start()
        showIntro()
        offerCrashReport()
    }

    override fun onResume() {
        super.onResume()
        when (screen) {
            "cleanHome" -> showCleanHome()
            "chatHome" -> showChatHome()
            "spaceHome" -> showSpaceHome()
            "bgHome" -> showBgHome()
            "hiddenHome" -> showHiddenHome()
        }
    }

    override fun onBackPressed() {
        val g = group
        when {
            screen == "intro" -> super.onBackPressed()
            screen != "group" && g != null -> showGroup(g)
            else -> showIntro()
        }
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(
        s: String,
        size: Float = 14f,
        color: Int = dark,
        bold: Boolean = false,
        pad: Int = 5
    ): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        t.setPadding(0, dp(pad), 0, dp(pad))
        box.addView(t)
        return t
    }

    private fun title(s: String) {
        text("> ${s}_", size = 22f, color = green, bold = true, pad = 4)
        sep()
    }

    private fun sep() {
        text("====================================", size = 12f, color = grey, pad = 1)
    }

    private fun section(s: String) {
        text("## $s ##", size = 16f, color = green, bold = true, pad = 12)
    }

    private fun buttonBg(): StateListDrawable {
        val normal = GradientDrawable()
        normal.setColor(Color.BLACK)
        normal.setStroke(dp(1), green)
        val pressed = GradientDrawable()
        pressed.setColor(Color.parseColor("#004D14"))
        pressed.setStroke(dp(1), green)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        sl.addState(intArrayOf(), normal)
        return sl
    }

    private fun button(label: String, action: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(green)
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        b.textSize = 14f
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.background = buttonBg()
        b.stateListAnimator = null
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(6), 0, dp(6))
        box.addView(b, lp)
    }

    private fun backButton() {
        val g = group
        if (g != null) button(tr("[<] Indietro")) { showGroup(g) } else button(tr("[<] Menu principale")) { showIntro() }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                // niente da fare
            }
        }
    }

    private fun openApp(pkg: String) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            )
        } catch (e: Exception) {
            // niente da fare
        }
    }

    private fun fmt(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    // ---------- Menu ----------

    private fun showIntro() {
        token++
        worker?.interrupt()
        group = null
        screen = "intro"
        box.removeAllViews()
        text("> GhostALO_", size = 26f, color = green, bold = true, pad = 4)
        text(tr("Versione") + " 5.2", size = 12f, color = grey, pad = 1)
        text(tr("Nessun accesso a Internet: i dati restano nel telefono."), size = 12f, color = grey, pad = 1)
        sep()
        if (Watch.isStale(this)) {
            text(tr("[!!] Gli avvisi in background sono in ritardo: Android potrebbe limitarli. Apri Strumenti > Avvisi."), size = 12f, color = orange, pad = 4)
        }
        button(tr("[>] CONTROLLO RAPIDO\n     un tocco: dice se è tutto a posto")) { startQuick() }
        text(tr("Oppure scegli un'area:"), size = 12f, color = grey, pad = 8)
        button(tr("[S] Sicurezza\n     dispositivo, app spia, APK")) { showGroup("sec") }
        button(tr("[P] Privacy\n     permessi, tracker, rete, profili")) { showGroup("priv") }
        button(tr("[C] Pulizia e prestazioni\n     file inutili, chat, spazio, batteria")) { showGroup("clean") }
        button(tr("[T] Strumenti\n     avvisi, impostazioni, storico, widget")) { showGroup("tools") }
        button(tr("[?] Informazioni e limiti")) { showAbout() }
    }

    private fun showGroup(g: String) {
        token++
        worker?.interrupt()
        group = g
        screen = "group"
        box.removeAllViews()
        when (g) {
            "sec" -> {
                title(tr("SICUREZZA"))
                button(tr("Sicurezza del dispositivo")) { showSecurity() }
                button(tr("Scansione privacy e app spia")) { startScan() }
                button(tr("App senza icona e cartelle nascoste")) { showHiddenHome() }
                button(tr("Controllo degli APK scaricati")) { showApkHome() }
            }
            "priv" -> {
                title("PRIVACY")
                button(tr("Permessi: chi usa cosa")) { startPermScan() }
                button(tr("Rilevatore di tracker")) { startTrackerScan() }
                button(tr("Rete Wi-Fi e reti pubbliche")) { showNet() }
                button(tr("Spazio privato e profili")) { showProfiles() }
                button(tr("Cookie e tracciamento")) { showCookies() }
            }
            "clean" -> {
                title(tr("PULIZIA_E_PRESTAZIONI"))
                button(tr("Pulizia file e cache")) { showCleanHome() }
                button(tr("Pulizia WhatsApp e Telegram")) { showChatHome() }
                button(tr("Analisi dello spazio per tipo")) { showSpaceHome() }
                button(tr("App attive in background e batteria")) { showBgHome() }
                button(tr("File grandi e vecchi")) { showBigHome() }
                button(tr("Cestino")) { showTrash() }
                button(tr("Cartelle di app disinstallate")) { showOrphanHome() }
            }
            else -> {
                title(tr("STRUMENTI"))
                button(tr("Avvisi in background e widget")) { showWatch() }
                button(tr("Impostazioni avvisi")) { showSettings() }
                button(tr("App fidate")) { showTrusted() }
                button(tr("Storico avvisi")) { showHistory() }
                button(tr("Elenco app spia: aggiorna da file")) { showSpyDb() }
                button(tr("Esporta elenco delle app")) { exportApps() }
            }
        }
        button(tr("[<] Menu principale")) { showIntro() }
    }

    // ---------- Controllo rapido ----------

    private fun startQuick() {
        token++
        val my = token
        screen = "quick"
        box.removeAllViews()
        title(tr("CONTROLLO_RAPIDO"))
        text(tr("Controllo il telefono…"), color = grey)
        work {
            val sec = try {
                SecurityCheck.run(this)
            } catch (e: Exception) {
                null
            }
            val snap = try {
                Watch.snapshot(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) showQuick(sec, snap)
            }
        }
    }

    private fun showQuick(sec: SecurityResult?, snap: Snap?) {
        screen = "quickReport"
        box.removeAllViews()
        title(tr("CONTROLLO_RAPIDO"))
        if (sec == null && snap == null) {
            text(tr("Non sono riuscito a completare il controllo."), color = red)
            button(tr("[>] Riprova")) { startQuick() }
            backButton()
            return
        }
        val rows = ArrayList<Triple<Int, String, (() -> Unit)?>>()
        if (snap != null) {
            fun lab(p: String): String = snap.labels[p] ?: p
            for (p in snap.spy) {
                val why = if (snap.spyBy[p] == "cert") tr("firma uguale a quella di uno stalkerware noto") else tr("nome del pacchetto presente nell'elenco")
                rows.add(Triple(3, tr("APP SPIA NOTA: ") + lab(p) + "\n   (" + why + ")", { openApp(p) }))
            }
            for (p in snap.acc) rows.add(Triple(2, tr("Accessibilità attiva: ") + lab(p), { openApp(p) }))
            for (p in snap.admin) rows.add(Triple(2, tr("Amministratore del dispositivo: ") + lab(p), { openApp(p) }))
            for (p in snap.listeners) rows.add(Triple(2, tr("Legge le notifiche: ") + lab(p), { openApp(p) }))
            for (p in snap.risk) {
                if (p !in snap.spy) rows.add(Triple(2, tr("Installata da APK con permessi sensibili: ") + lab(p), { openApp(p) }))
            }
        }
        if (sec != null) {
            for (c in sec.checks) {
                if (c.level >= 2 && !c.title.startsWith(tr("Accessibilità"))) {
                    val act = c.action
                    rows.add(Triple(c.level, c.title, if (act != null) ({ openSettings(act) }) else null))
                }
            }
        }
        rows.sortByDescending { it.first }
        val worst = rows.maxOfOrNull { it.first } ?: 0
        val incomplete = sec == null || snap == null
        val known = snap != null && snap.spy.isNotEmpty()
        if (known) {
            text(tr("[XX] MINACCIA NOTA RILEVATA"), size = 20f, color = red, bold = true, pad = 8)
            text(tr("Corrispondenza con un indicatore dell'elenco pubblico di stalkerware."), size = 12f, color = red, pad = 2)
        } else if (worst >= 3) {
            text(tr("[XX] PROBLEMI DA RISOLVERE (") + rows.size + ")", size = 20f, color = red, bold = true, pad = 8)
        } else if (worst >= 2) {
            text(tr("[!!] DA VERIFICARE (") + rows.size + ")", size = 20f, color = orange, bold = true, pad = 8)
            text(tr("Segnali che richiedono un controllo tuo: non sono la prova di un'infezione."), size = 12f, color = grey, pad = 2)
        } else if (incomplete) {
            text(tr("[??] CONTROLLO INCOMPLETO"), size = 20f, color = orange, bold = true, pad = 8)
        } else {
            text(tr("[OK] NESSUN INDICATORE RILEVATO"), size = 20f, color = green, bold = true, pad = 8)
        }
        section(tr("CONTROLLI ESEGUITI"))
        val sp = Stalker.load(this)
        text(
            (if (snap != null) "[v] " else "[--] ") + tr("App installate: elenco spia (") + (sp.first.size + sp.second.size) + tr(" indicatori), accessibilità, amministratori, lettori di notifiche, APK con permessi sensibili") +
                (if (snap == null) tr(" - NON COMPLETATO") else ""),
            size = 12f, color = if (snap != null) grey else orange, pad = 2
        )
        text(
            (if (sec != null) "[v] " else "[--] ") + tr("Impostazioni del dispositivo: blocco schermo, aggiornamenti, debug USB, root, DNS e altro") +
                (if (sec == null) tr(" - NON COMPLETATO") else ""),
            size = 12f, color = if (sec != null) grey else orange, pad = 2
        )
        text(
            "[--] " + tr("Non eseguiti qui: scansione completa dei permessi e tracker (usa il pulsante qui sotto)."),
            size = 12f, color = grey, pad = 2
        )
        if (sec != null) {
            text(tr("Punteggio sicurezza dispositivo: ") + sec.score + "/100", size = 13f, color = grey, pad = 2)
        }
        if (rows.isNotEmpty()) {
            text(tr("Tocca una riga per aprire la scheda o l'impostazione."), size = 12f, color = grey, pad = 8)
            for ((level, msg, act) in rows) {
                val t = text(
                    (if (level >= 3) "[XX] " else "[!!] ") + msg,
                    size = 13f, color = if (level >= 3) red else orange, pad = 7
                )
                if (act != null) t.setOnClickListener { act() }
            }
        } else {
            text(
                if (incomplete) tr("Nessun indizio nei controlli completati, ma il controllo è incompleto: ripetilo.") else tr("Nessun indizio trovato nei controlli eseguiti."),
                size = 13f, color = grey, pad = 6
            )
        }
        text(
            tr("Nessun indicatore non significa nessuno spyware: è un controllo di indizi, non un antivirus, e non garantisce che il telefono sia pulito."),
            size = 11f, color = grey, pad = 8
        )
        button(tr("[>] Scansione completa (app spia e permessi)")) { startScan() }
        button(tr("[>] Tutti i dettagli di sicurezza")) { showSecurity() }
        button(tr("[>] Ripeti controllo")) { startQuick() }
        backButton()
    }

    private fun showAbout() {
        screen = "about"
        box.removeAllViews()
        title("INFO")
        text(
            tr("Questa app usa solo le funzioni ufficiali di Android. Non ha il permesso Internet, non invia nulla e non elimina niente senza la tua conferma."),
            color = dark
        )
        section(tr("Fonti"))
        text(
            tr("Elenco di app spia (nomi dei pacchetti e firme): progetto stalkerware-indicators di Echap, licenza CC-BY 4.0. L'elenco è una copia inclusa nell'app e può non essere completo."),
            size = 13f, color = grey
        )
        section(tr("Limiti"))
        text(
            tr("• Non può leggere i dati di altre app né vedere in tempo reale chi usa fotocamera e microfono.\n• Non può svuotare la cache o la cronologia di altre app: Android lo vieta.\n• Non è un antivirus: confronta app e firme con un elenco noto e cerca indizi.\n• I risultati sono indizi, non prove.\n• Se sospetti un controllo nascosto, il ripristino di fabbrica è la soluzione più sicura (salva prima i dati e cambia le password da un altro dispositivo)."),
            size = 13f, color = grey
        )
        backButton()
    }

    // ---------- Sicurezza del dispositivo ----------

    private fun showSecurity() {
        screen = "security"
        box.removeAllViews()
        title(tr("SICUREZZA_DISPOSITIVO"))
        val res = SecurityCheck.run(this)
        val col = if (res.score >= 80) green else if (res.score >= 60) orange else red
        text(tr("PUNTEGGIO: ") + res.score + "/100", size = 24f, color = col, bold = true, pad = 8)
        for (c in res.checks) {
            val tag = when (c.level) {
                0 -> "[OK]"
                1 -> "[--]"
                2 -> "[!!]"
                else -> "[XX]"
            }
            val color = when (c.level) {
                0 -> dark
                1 -> grey
                2 -> orange
                else -> red
            }
            val t = text(tag + " " + c.title + "\n     " + c.detail, size = 13f, color = color, pad = 7)
            val act = c.action
            if (act != null) t.setOnClickListener { openSettings(act) }
        }
        text(tr("Tocca una riga con [!!] o [XX] per aprire l'impostazione relativa."), size = 12f, color = grey, pad = 10)
        button(tr("[>] Ripeti controllo")) { showSecurity() }
        backButton()
    }

    // ---------- Scansione privacy ----------

    private fun startScan() {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            runScan()
        } else {
            screen = "privacyPerm"
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            2 -> if (granted) doEnableWatch() else showWatch()
            3 -> showWatch()
            else -> runScan()
        }
    }

    private fun runScan() {
        token++
        val my = token
        screen = "privacy"
        box.removeAllViews()
        title(tr("SCANSIONE_PRIVACY"))
        text(tr("Analisi in corso…"), size = 18f, bold = true)
        text(tr("Può richiedere qualche secondo."), color = grey)
        work {
            val report = try {
                scan()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) {
                    if (report == null) {
                        box.removeAllViews()
                        title(tr("ERRORE"))
                        text(tr("Errore durante l'analisi."), color = red)
                        button(tr("[>] Riprova")) { startScan() }
                        backButton()
                    } else {
                        showReport(report)
                    }
                }
            }
        }
    }

    private fun showReport(r: Report) {
        box.removeAllViews()
        title(tr("RISULTATO_PRIVACY"))

        if (r.spyHits.isNotEmpty()) {
            text(
                tr("[XX] APP SPIA NOTE RILEVATE: ") + r.spyHits.size,
                size = 17f, color = red, bold = true, pad = 8
            )
            for (h in r.spyHits) text(">> $h", color = red)
            text(
                tr("Tocca l'app nell'elenco qui sotto per aprirne la scheda e disinstallarla. ATTENZIONE: se sospetti che l'abbia installata un partner o un familiare violento, rimuoverla potrebbe avvisarlo. Valuta prima di contattare un centro antiviolenza (in Italia: 1522, gratuito e attivo 24 ore su 24)."),
                size = 12f, color = orange
            )
        } else {
            text(
                tr("[OK] Nessuna corrispondenza con ") + r.stalkerDbSize + tr(" indicatori noti di app spia."),
                color = green, bold = true, pad = 8
            )
        }

        val high = r.flagged.count { it.score >= 4 }
        val mid = r.flagged.size - high
        if (high > 0) {
            text(trf("[!!] %s app con rischio ALTO, %s con rischio medio.", high, mid), size = 15f, color = red, bold = true)
        } else if (mid > 0) {
            text(trf("[!!] %s app da controllare (rischio medio).", mid), size = 15f, color = orange, bold = true)
        } else {
            text(tr("[OK] Nessun segnale evidente nelle altre app."), size = 15f, color = green, bold = true)
        }
        text(
            tr("Sono indizi, non prove: molte app legittime usano questi permessi. Controlla che ti siano familiari e che ti servano."),
            size = 12f, color = grey
        )

        section(tr("APP DA CONTROLLARE"))
        if (r.flagged.isEmpty()) {
            text(tr("Nessuna app con segnali significativi."), color = green)
        }
        for (f in r.flagged) {
            val level = if (f.score >= 10) tr("APP SPIA NOTA") else if (f.score >= 4) tr("RISCHIO ALTO") else tr("RISCHIO MEDIO")
            val t = text(
                f.label + "  [" + level + "]\n" +
                    f.reasons.joinToString("\n") { "  - $it" } +
                    tr("\n  (tocca per aprire la scheda dell'app)"),
                size = 13f,
                color = if (f.score >= 4) red else orange,
                pad = 8
            )
            t.setOnClickListener { openApp(f.pkg) }
        }
        if (r.quietCount > 0) {
            text(trf("Altre app installate senza segnali rilevanti: %s", r.quietCount), size = 12f, color = grey)
        }

        section(tr("ACCESSIBILITA ATTIVA"))
        text(
            tr("Possono leggere lo schermo e ciò che scrivi. Normali per app come TalkBack; sospetti se non li hai attivati tu."),
            size = 12f, color = grey
        )
        if (r.accLines.isEmpty()) text(tr("[OK] Nessuno attivo."), color = green)
        for (l in r.accLines) text("- $l", size = 13f)

        section(tr("TASTIERE ABILITATE"))
        for (l in r.keyboardLines) text("- $l", size = 13f)

        section(tr("ACCESSO FOTOCAMERA"))
        text(if (r.camApps.isEmpty()) tr("Nessuna app non di sistema.") else r.camApps.joinToString(", "), size = 13f)

        section(tr("ACCESSO MICROFONO"))
        text(if (r.micApps.isEmpty()) tr("Nessuna app non di sistema.") else r.micApps.joinToString(", "), size = 13f)

        section(tr("FOTO DA ALTRE APP"))
        if (r.photoLines == null) {
            text(
                tr("Per questo controllo serve il permesso di leggere le foto. Rilancia il controllo e concedilo."),
                size = 13f, color = grey
            )
        } else if (r.photoLines.isEmpty()) {
            text(tr("[OK] Nessuna foto creata da app diverse dalla fotocamera."), color = green)
        } else {
            text(
                tr("Foto create da app che non sono la fotocamera. Non è per forza un problema (app di blocco che scattano il selfie a chi sbaglia il PIN, filtri, scanner)."),
                size = 12f, color = grey
            )
            for (l in r.photoLines) text("- $l", size = 13f)
        }

        section(tr("SPAZIO"))
        text(r.storageLine, size = 13f)
        if (r.bigFiles != null && r.bigFiles.isNotEmpty()) {
            text(tr("File multimediali più grandi:"), bold = true, size = 13f)
            for (l in r.bigFiles) text("- $l", size = 12f)
        }

        section(tr("SCORCIATOIE"))
        button(tr("[>] Impostazioni Privacy (accessi recenti)")) {
            openSettings("android.settings.PRIVACY_SETTINGS")
        }
        button(tr("[>] Impostazioni Accessibilità")) {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        button(tr("[>] Sicurezza (amministratori dispositivo)")) {
            openSettings(Settings.ACTION_SECURITY_SETTINGS)
        }
        button(tr("[>] Elenco di tutte le app")) {
            openSettings(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        }
        button(tr("[>] Ripeti il controllo")) { startScan() }
        backButton()

        text(
            tr("Limiti: l'app non può leggere i dati di altre app né dimostrare che nessuno registri ciò che digiti. Se hai il fondato sospetto di un controllo nascosto, il ripristino di fabbrica è la soluzione più sicura."),
            size = 11f, color = grey, pad = 14
        )
    }

    // ---------- Tracker ----------

    private fun startTrackerScan() {
        token++
        val my = token
        screen = "trackers"
        box.removeAllViews()
        title(tr("RILEVATORE_TRACKER"))
        text(
            tr("Cerco nel codice delle app installate le librerie pubblicitarie e di tracciamento più note. Può richiedere uno o due minuti."),
            size = 13f, color = grey
        )
        val pv = text(tr("Avvio…"), size = 16f, bold = true, pad = 10)
        work {
            val res = try {
                Trackers.scanAll(this) { done, total ->
                    runOnUiThread {
                        if (my == token) pv.text = trf("Analisi: %s / %s app", done, total)
                    }
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) {
                    if (res == null) {
                        box.removeAllViews()
                        title(tr("ERRORE"))
                        text(tr("Errore durante l'analisi."), color = red)
                        backButton()
                    } else {
                        showTrackerReport(res)
                    }
                }
            }
        }
    }

    private fun showTrackerReport(res: TrackerResult) {
        screen = "trackerReport"
        box.removeAllViews()
        title(tr("RISULTATO_TRACKER"))
        val withT = res.apps.filter { it.trackers.isNotEmpty() }.sortedByDescending { it.trackers.size }
        if (withT.isEmpty()) {
            text(tr("[OK] Nessun tracker noto trovato in ") + res.analyzed + tr(" app."), color = green, bold = true)
        } else {
            text(
                "[!!] " + withT.size + tr(" app su ") + res.analyzed + tr(" contengono tracker noti."),
                size = 16f, color = orange, bold = true, pad = 8
            )
            val counts = HashMap<String, Int>()
            for (a in withT) for (t in a.trackers) counts[t] = (counts[t] ?: 0) + 1

            section(tr("TRACKER PIU DIFFUSI"))
            for (e in counts.entries.sortedByDescending { it.value }.take(10)) {
                text("- " + e.key + ": " + e.value + tr(" app"), size = 13f)
            }

            section(tr("DETTAGLIO PER APP"))
            text(tr("Tocca un'app per aprirne la scheda."), size = 12f, color = grey)
            for (a in withT.take(40)) {
                val t = text(
                    a.label + " (" + a.trackers.size + ")\n" +
                        a.trackers.sorted().joinToString("\n") { "  - $it" },
                    size = 13f,
                    color = if (a.trackers.size >= 5) orange else dark,
                    pad = 8
                )
                t.setOnClickListener { openApp(a.pkg) }
            }
            if (withT.size > 40) text(tr("… e altre ") + (withT.size - 40) + tr(" app."), size = 12f, color = grey)
        }
        text(
            tr("Un tracker non significa spionaggio: molte app gratuite li usano per pubblicità e statistiche. Se un'app ne ha molti e non ti serve, valuta di disinstallarla. Il rilevamento si basa sui nomi delle librerie: le app \"offuscate\" possono sfuggire."),
            size = 12f, color = grey, pad = 12
        )
        button(tr("[>] Ripeti analisi")) { startTrackerScan() }
        backButton()
    }

    // ---------- Avvisi in background e widget ----------

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun showWatch() {
        screen = "watch"
        box.removeAllViews()
        title(tr("AVVISI_E_WIDGET"))

        section(tr("AVVISI IN BACKGROUND"))
        text(
            tr("A intervalli regolari (di solito 30 minuti, lo cambi in Impostazioni avvisi) l'app controlla in silenzio se è comparso qualcosa di nuovo e ti manda una notifica. Controlla: app spia note, nuovi servizi di accessibilità, nuovi amministratori del dispositivo, nuovi lettori di notifiche e app installate da APK con permessi sensibili. Tutto resta nel telefono."),
            size = 12f, color = grey
        )
        val on = Watch.isEnabled(this)
        text(
            if (on) tr("[OK] Avvisi ATTIVI") else tr("[--] Avvisi spenti"),
            color = if (on) green else grey, bold = true, pad = 8
        )
        if (!notificationsAllowed()) {
            text(
                tr("[NO] Le notifiche non sono consentite: senza non vedrai gli avvisi."),
                size = 13f, color = red
            )
            button(tr("[>] Consenti le notifiche")) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        }
        if (on) {
            button(tr("[>] Spegni gli avvisi")) {
                Watch.disable(this)
                showWatch()
            }
        } else {
            button(tr("[>] Attiva gli avvisi")) { enableWatch() }
        }

        val cb = CheckBox(this)
        cb.text = tr("Avvisa anche per ogni nuova app installata da APK")
        cb.setTextColor(green)
        cb.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        cb.textSize = 13f
        cb.buttonTintList = ColorStateList.valueOf(green)
        cb.isChecked = Watch.alertNewApk(this)
        cb.setOnCheckedChangeListener { _, v -> Watch.setAlertNewApk(this, v) }
        box.addView(cb)

        text(
            tr("La frequenza è indicativa: Android può ritardare i controlli per risparmiare batteria, e alcuni telefoni li limitano ancora di più."),
            size = 12f, color = grey, pad = 4
        )
        if (Watch.isStale(this)) {
            text(
                tr("[!!] L'ultimo controllo è molto più vecchio del previsto: il telefono sta probabilmente limitando l'app. Imposta il risparmio energetico su \"Nessuna restrizione\" (pulsante più sotto)."),
                size = 13f, color = orange, pad = 6
            )
        }
        val fail = Watch.lastFailure(this)
        if (fail != 0L && fail >= Watch.prefs(this).getLong("st_time", 0L)) {
            text(tr("[!!] Ultimo controllo non riuscito: ") + DateFormat.format("dd/MM/yyyy HH:mm", Date(fail)), size = 13f, color = orange, pad = 4)
        }
        val t = Watch.prefs(this).getLong("st_time", 0L)
        if (t == 0L) {
            text(tr("Nessun controllo eseguito finora."), size = 13f, color = grey, pad = 8)
        } else {
            val pr = Watch.prefs(this)
            text(
                tr("Ultimo controllo: ") + DateFormat.format("dd/MM/yyyy HH:mm", Date(t)) + tr("\nApp spia: ") + pr.getInt("st_spy", 0) +
                    tr("   Accessibilità: ") + pr.getInt("st_acc", 0) +
                    "   Admin: " + pr.getInt("st_adm", 0) + tr("\nLettori notifiche: ") + pr.getInt("st_lst", 0) +
                    tr("   APK sensibili: ") + pr.getInt("st_risk", 0),
                size = 13f, pad = 8
            )
        }
        button(tr("[>] Controlla ora")) { checkNow() }
        button(tr("[>] Invia un avviso di prova")) {
            if (notificationsAllowed()) {
                Watch.sendTest(this)
            } else {
                Toast.makeText(this, tr("Prima consenti le notifiche."), Toast.LENGTH_SHORT).show()
            }
        }

        section(tr("RISPARMIO ENERGETICO"))
        text(
            tr("Alcuni telefoni (Xiaomi, Huawei, Samsung, Oppo…) fermano le app in background per risparmiare batteria e gli avvisi possono arrivare in ritardo. Se succede, imposta questa app su \"Nessuna restrizione\"."),
            size = 12f, color = grey
        )
        button(tr("[>] Impostazioni batteria")) {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }

        section("WIDGET")
        text(
            tr("Per mettere una tessera piccola sulla Home: tieni premuto su uno spazio vuoto, scegli Widget e cerca \"GhostALO (icona piccola)\": occupa un solo riquadro e mostra OK, !! o XX. La versione \"esteso\" mostra più dettagli e il tasto [R]. L'icona normale dell'app si trascina dal menu delle app."),
            size = 13f, color = dark
        )
        backButton()
    }

    private fun enableWatch() {
        if (!notificationsAllowed()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        } else {
            doEnableWatch()
        }
    }

    private fun doEnableWatch() {
        try {
            Watch.enable(this)
        } catch (e: Exception) {
            Toast.makeText(this, tr("Impossibile attivare gli avvisi su questo telefono."), Toast.LENGTH_LONG).show()
            showWatch()
            return
        }
        showWatch()
        val app = applicationContext
        work {
            try {
                Watch.runCheck(app)
            } catch (e: Exception) {
                // riproverà al prossimo giro
            }
            runOnUiThread { if (screen == "watch") showWatch() }
        }
    }

    private fun checkNow() {
        Toast.makeText(this, tr("Controllo in corso…"), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        work {
            try {
                Watch.refreshAndCheck(app)
            } catch (e: Exception) {
                // ignora
            }
            runOnUiThread { if (screen == "watch") showWatch() }
        }
    }

    // ---------- File grandi e vecchi / cartelle orfane ----------

    private var bigMb = 100
    private var bigDays = 90

    private fun showBigHome() {
        screen = "bigHome"
        box.removeAllViews()
        title(tr("FILE_GRANDI_E_VECCHI"))
        text(
            tr("Cerca i file pesanti che non modifichi da tempo (video, archivi, installer…). Vedi l'elenco e confermi tu prima di eliminare."),
            size = 13f, color = grey
        )
        if (!Cleaner.hasAllFiles()) {
            text(tr("[NO] Accesso ai file: non concesso"), color = red, bold = true, pad = 10)
            button(tr("[>] Concedi accesso a tutti i file")) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    openSettings(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
            }
            backButton()
            return
        }
        section(tr("DIMENSIONE MINIMA"))
        for (m in listOf(50, 100, 500)) button((if (m == bigMb) "[x] " else "[ ] ") + m + " MB") { bigMb = m; showBigHome() }
        section(tr("NON MODIFICATI DA"))
        for (d in listOf(30, 90, 365)) button((if (d == bigDays) "[x] " else "[ ] ") + trf("%s giorni", d)) { bigDays = d; showBigHome() }
        button(tr("[>] Avvia scansione")) { startBigScan() }
        backButton()
    }

    private fun startBigScan() {
        token++
        val my = token
        screen = "bigScan"
        box.removeAllViews()
        title(tr("FILE_GRANDI_E_VECCHI"))
        text(tr("Scansione in corso…"), color = grey)
        button(tr("[x] Annulla")) { showBigHome() }
        val mb = bigMb
        val days = bigDays
        work {
            val res = try {
                Cleaner.scanBigOld(mb, days)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { if (my == token) showBigReport(res) }
        }
    }

    private fun showBigReport(files: List<java.io.File>) {
        screen = "bigReport"
        box.removeAllViews()
        title(tr("FILE_GRANDI_E_VECCHI"))
        if (files.isEmpty()) {
            text(tr("Nessun file trovato con questi criteri."), color = green)
            button(tr("[<] Cambia criteri")) { showBigHome() }
            backButton()
            return
        }
        val chosen = HashSet<java.io.File>()
        val stamps = files.associateWith { Pair(it.length(), it.lastModified()) }
        val total = text("", size = 13f, color = orange, bold = true, pad = 8)
        fun upd() {
            total.text = trf("Selezionati: %s file, %s", chosen.size, fmt(chosen.sumOf { it.length() }))
        }
        text(tr("Spunta i file da eliminare (i più pesanti per primi)."), size = 12f, color = grey)
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        for (f in files) {
            val age = ((System.currentTimeMillis() - f.lastModified()) / (24L * 3600L * 1000L)).toInt()
            check(f.name + "\n" + fmt(f.length()) + " - " + trf("%s giorni", age) + "\n" + (f.parent ?: "").removePrefix(root), false) {
                if (it) chosen.add(f) else chosen.remove(f)
                upd()
            }
        }
        upd()
        button(tr("[>] Elimina i selezionati")) {
            if (chosen.isEmpty()) {
                Toast.makeText(this, tr("Nessun file selezionato."), Toast.LENGTH_SHORT).show()
            } else {
                val sel = chosen.toList()
                AlertDialog.Builder(this)
                    .setTitle(tr("Eliminare questi file?"))
                    .setMessage(
                        trf("%s file, %s. ", sel.size, fmt(sel.sumOf { it.length() })) +
                            if (Trash.active(this)) tr("Vanno nel cestino e puoi ripristinarli.") else tr("L'operazione non si può annullare.")
                    )
                    .setPositiveButton(tr("Elimina")) { _, _ ->
                        val app = applicationContext
                        work {
                            val r = Cleaner.deleteFiles(app, sel, stamps)
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    trf(if (Trash.active(this)) "Spostati nel cestino: %s (%s non spostati)" else "Liberati %s (%s non eliminati)", fmt(r.first), r.second),
                                    Toast.LENGTH_LONG
                                ).show()
                                showBigHome()
                            }
                        }
                    }
                    .setNegativeButton(tr("Annulla"), null)
                    .show()
            }
        }
        backButton()
    }

    private fun showOrphanHome() {
        screen = "orphanHome"
        box.removeAllViews()
        title(tr("APP_DISINSTALLATE"))
        text(
            tr("Cerca cartelle con nome da app Android (per esempio com.nome.app) di app che non sono più installate. Sono solo indizi: guarda l'elenco prima di eliminare. Le cartelle interne di Android/data non sono raggiungibili da nessuna app."),
            size = 13f, color = grey
        )
        if (!Cleaner.hasAllFiles()) {
            text(tr("[NO] Accesso ai file: non concesso"), color = red, bold = true, pad = 10)
            button(tr("[>] Concedi accesso a tutti i file")) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    openSettings(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
            }
            backButton()
            return
        }
        button(tr("[>] Avvia scansione")) { startOrphanScan() }
        backButton()
    }

    private fun startOrphanScan() {
        token++
        val my = token
        screen = "orphanScan"
        box.removeAllViews()
        title(tr("APP_DISINSTALLATE"))
        text(tr("Scansione in corso…"), color = grey)
        button(tr("[x] Annulla")) { showOrphanHome() }
        work {
            val res = try {
                Cleaner.orphanDirs(this)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { if (my == token) showOrphanReport(res) }
        }
    }

    private fun showOrphanReport(list: List<Pair<java.io.File, Long>>) {
        screen = "orphanReport"
        box.removeAllViews()
        title(tr("APP_DISINSTALLATE"))
        if (list.isEmpty()) {
            text(tr("Nessuna cartella sospetta trovata."), color = green)
            backButton()
            return
        }
        val chosen = HashSet<java.io.File>()
        val sizes = list.toMap()
        val total = text("", size = 13f, color = orange, bold = true, pad = 8)
        fun upd() {
            total.text = trf("Selezionate: %s cartelle, %s", chosen.size, fmt(chosen.sumOf { sizes[it] ?: 0L }))
        }
        text(tr("Controlla: se la cartella contiene dati che ti servono (per esempio i backup di un'app), non selezionarla."), size = 12f, color = grey)
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        for ((d, sz) in list) {
            check(d.name + "\n" + fmt(sz) + "  " + (d.parent ?: "").removePrefix(root), false) {
                if (it) chosen.add(d) else chosen.remove(d)
                upd()
            }
        }
        upd()
        button(tr("[>] Elimina le selezionate")) {
            if (chosen.isEmpty()) {
                Toast.makeText(this, tr("Nessuna cartella selezionata."), Toast.LENGTH_SHORT).show()
            } else {
                val sel = chosen.toList()
                AlertDialog.Builder(this)
                    .setTitle(tr("Eliminare queste cartelle?"))
                    .setMessage(
                        if (Trash.active(this)) {
                            trf("%s cartelle, %s, con tutto il loro contenuto. Vanno nel cestino e puoi ripristinarle.", sel.size, fmt(sel.sumOf { sizes[it] ?: 0L }))
                        } else {
                            trf("%s cartelle, %s, con tutto il loro contenuto. L'operazione non si può annullare.", sel.size, fmt(sel.sumOf { sizes[it] ?: 0L }))
                        }
                    )
                    .setPositiveButton(tr("Elimina")) { _, _ ->
                        work {
                            var fail = 0
                            if (Trash.active(this)) {
                                fail = Trash.moveAll(applicationContext, sel).second
                            } else {
                                for (d in sel) if (!Cleaner.deleteTree(d)) fail++
                            }
                            runOnUiThread {
                                Toast.makeText(this, trf("Fatto (%s non eliminate)", fail), Toast.LENGTH_LONG).show()
                                showOrphanHome()
                            }
                        }
                    }
                    .setNegativeButton(tr("Annulla"), null)
                    .show()
            }
        }
        backButton()
    }

    // ---------- Cestino ----------

    private fun showTrash() {
        screen = "trash"
        box.removeAllViews()
        title(tr("CESTINO"))
        text(
            tr("I file eliminati dalle pulizie finiscono qui e restano recuperabili. Dopo il numero di giorni scelto in Impostazioni avvisi vengono cancellati davvero."),
            size = 13f, color = grey
        )
        if (!Cleaner.hasAllFiles()) {
            text(tr("[NO] Accesso ai file: non concesso"), color = red, bold = true, pad = 10)
            allFilesButton()
            backButton()
            return
        }
        text(
            (if (Trash.enabled(this)) tr("[OK] Cestino attivo") else tr("[--] Cestino spento: le pulizie eliminano subito")) +
                trf(" - %s giorni", Trash.days(this)),
            color = if (Trash.enabled(this)) green else orange, bold = true, pad = 8
        )
        val list = Trash.batches(this)
        if (list.isEmpty()) {
            text(tr("Il cestino è vuoto."), color = grey, pad = 8)
        } else {
            text(trf("Occupa %s.", fmt(list.sumOf { it.bytes })), size = 13f, color = orange, pad = 8)
            for (b in list) {
                text(
                    DateFormat.format("dd/MM/yyyy HH:mm", Date(b.batch)).toString() + "\n" + trf("%s elementi, %s", b.count, fmt(b.bytes)),
                    size = 13f, pad = 6
                )
                button(tr("[>] Ripristina")) {
                    val app = applicationContext
                    work {
                        val n = Trash.restore(app, b.batch)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                trf("Ripristinati %s elementi", n.first) + if (n.second > 0) trf(" (%s non ripristinati)", n.second) else "",
                                Toast.LENGTH_LONG
                            ).show()
                            showTrash()
                        }
                    }
                }
            }
            button(tr("[>] Svuota il cestino adesso")) {
                AlertDialog.Builder(this)
                    .setTitle(tr("Svuotare il cestino?"))
                    .setMessage(tr("I file verranno eliminati definitivamente. L'operazione non si può annullare."))
                    .setPositiveButton(tr("Elimina")) { _, _ ->
                        val app = applicationContext
                        work {
                            val f = Trash.purge(app, true)
                            runOnUiThread {
                                Toast.makeText(this, tr("Spazio liberato: ") + fmt(f), Toast.LENGTH_LONG).show()
                                showTrash()
                            }
                        }
                    }
                    .setNegativeButton(tr("Annulla"), null)
                    .show()
            }
        }
        backButton()
    }

    // ---------- Elenco app spia da file / esporta elenco app ----------

    private fun showSpyDb() {
        screen = "spyDb"
        box.removeAllViews()
        title(tr("ELENCO_APP_SPIA"))
        val (p, c) = Stalker.load(this)
        text(
            tr("Elenco in uso: ") + (if (Stalker.isCustom(this)) tr("importato da te") else tr("incluso nell'app")) +
                "\n" + trf("%s app e %s firme note", p.size, c.size),
            size = 14f, color = green, bold = true, pad = 8
        )
        text(
            tr("Puoi aggiornare l'elenco senza Internet: scarica dal sito di Echap (github.com/AssoEchap/stalkerware-indicators) il file ioc.yaml, poi sceglilo qui. Il file non viene inviato da nessuna parte."),
            size = 12f, color = grey
        )
        button(tr("[>] Scegli il file dell'elenco")) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            try {
                startActivityForResult(i, 7)
            } catch (e: Exception) {
                Toast.makeText(this, tr("Nessun gestore di file disponibile."), Toast.LENGTH_LONG).show()
            }
        }
        if (Stalker.isCustom(this)) {
            button(tr("[>] Torna all'elenco incluso")) {
                Stalker.resetToBundled(this)
                showSpyDb()
            }
        }
        backButton()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        work {
            val res = try {
                val txt = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (txt == null || txt.length > 8_000_000) null else Stalker.import(app, txt)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (res == null) {
                    Toast.makeText(this, tr("File non valido: l'elenco precedente resta in uso."), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, trf("Importati: %s app, %s firme", res.first, res.second), Toast.LENGTH_LONG).show()
                }
                showSpyDb()
            }
        }
    }

    private fun exportApps() {
        Toast.makeText(this, tr("Preparo l'elenco…"), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        work {
            val sb = StringBuilder()
            try {
                val pm = packageManager
                val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
                val pkgs = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong())) else pm.getInstalledPackages(flags)
                sb.append("GhostALO - ").append(tr("elenco delle app")).append(" ").append(DateFormat.format("dd/MM/yyyy HH:mm", Date())).append("\n\n")
                for (p in pkgs.sortedBy { it.packageName }) {
                    val ai = p.applicationInfo ?: continue
                    val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val inst = try { pm.getInstallSourceInfo(p.packageName).installingPackageName } catch (e: Exception) { null }
                    sb.append(pm.getApplicationLabel(ai)).append(" | ").append(p.packageName)
                        .append(" | v").append(p.versionName ?: "?")
                        .append(" | ").append(if (sys) "system" else "user")
                        .append(" | ").append(inst ?: "-")
                        .append(" | ").append(Stalker.certHashes(p).joinToString("+").take(16))
                        .append('\n')
                }
            } catch (e: Exception) {
                // elenco parziale
            }
            val text = sb.toString()
            runOnUiThread {
                try {
                    if (Cleaner.hasAllFiles()) {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val f = java.io.File(dir, "GhostALO_app_" + DateFormat.format("yyyyMMdd_HHmm", Date()) + ".txt")
                        f.writeText(text)
                        Toast.makeText(this, tr("Salvato in Download: ") + f.name, Toast.LENGTH_LONG).show()
                    } else {
                        val i = Intent(Intent.ACTION_SEND)
                        i.type = "text/plain"
                        i.putExtra(Intent.EXTRA_SUBJECT, "GhostALO - " + tr("elenco delle app"))
                        i.putExtra(Intent.EXTRA_TEXT, text)
                        startActivity(Intent.createChooser(i, tr("Condividi")))
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, tr("Impossibile salvare l'elenco."), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Cookie e tracciamento ----------

    private val browsers = listOf(
        "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
        "com.sec.android.app.sbrowser", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.microsoft.emmx", "com.opera.browser", "com.vivaldi.browser", "com.kiwibrowser.browser",
        "com.heytap.browser", "com.mi.globalbrowser", "com.android.browser"
    )

    private fun showCookies() {
        screen = "cookies"
        box.removeAllViews()
        title(tr("COOKIE_E_TRACCIAMENTO"))
        text(
            tr("I cookie stanno dentro ogni browser e Android non permette ad altre app di leggerli o cancellarli. Questa app non può farlo al posto tuo, ma ti porta dove si fa."),
            size = 13f, color = grey
        )
        section(tr("I TUOI BROWSER"))
        var n = 0
        for (pk in browsers) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pk, 0)).toString()
            } catch (e: Exception) {
                null
            } ?: continue
            n++
            button(trf("[>] %s\n     apri la scheda: Spazio → Cancella dati", label)) { openApp(pk) }
        }
        if (n == 0) text(tr("Nessun browser noto trovato."), size = 13f, color = grey)
        text(
            tr("Attenzione: \"Cancella dati\" elimina anche cronologia e accessi ai siti (ti sloghi ovunque). \"Svuota cache\" invece non tocca gli accessi."),
            size = 12f, color = orange, pad = 8
        )
        section(tr("CONSIGLI"))
        text(
            tr("- Blocca i cookie di terze parti: sono quelli che ti seguono da un sito all'altro. In Chrome: Impostazioni > Privacy e sicurezza > Cookie di terze parti > Blocca.\n- Chrome: usa anche \"Elimina dati di navigazione\" e scegli cookie e dati dei siti.\n- Firefox: Impostazioni > Elimina dati di navigazione all'uscita.\n- Brave e DuckDuckGo bloccano molti tracker già di base.\n- La navigazione in incognito non salva i cookie dopo la chiusura delle schede."),
            size = 13f, color = dark
        )
        backButton()
    }

    // ---------- Impostazioni, app fidate, storico, report errori ----------

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val cb = CheckBox(this)
        cb.text = label
        cb.setTextColor(green)
        cb.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        cb.textSize = 13f
        cb.buttonTintList = ColorStateList.valueOf(green)
        cb.isChecked = value
        cb.setOnCheckedChangeListener { _, v -> onChange(v) }
        box.addView(cb)
    }

    private fun showSettings() {
        screen = "settings"
        box.removeAllViews()
        title(tr("IMPOSTAZIONI"))
        section(tr("OGNI QUANTO CONTROLLARE"))
        val cur = Watch.periodMin(this)
        val opts = listOf(
            Pair(15, tr("15 minuti")),
            Pair(30, tr("30 minuti")),
            Pair(60, tr("1 ora")),
            Pair(360, tr("6 ore"))
        )
        for ((m, name) in opts) {
            button((if (m == cur) "[x] " else "[ ] ") + name) {
                Watch.setPeriodMin(this, m)
                showSettings()
            }
        }
        text(tr("Più spesso = avvisi più rapidi, ma un po' più di batteria."), size = 12f, color = grey)
        section(tr("DI COSA AVVISARMI"))
        text(tr("Le app spia note ti vengono sempre segnalate."), size = 12f, color = grey)
        check(tr("Nuovo servizio di accessibilità"), Watch.toggle(this, "alert_acc")) { Watch.setToggle(this, "alert_acc", it) }
        check(tr("Nuovo amministratore del dispositivo"), Watch.toggle(this, "alert_adm")) { Watch.setToggle(this, "alert_adm", it) }
        check(tr("Nuovo lettore di notifiche"), Watch.toggle(this, "alert_lst")) { Watch.setToggle(this, "alert_lst", it) }
        check(tr("App da APK con permessi sensibili"), Watch.toggle(this, "alert_risk")) { Watch.setToggle(this, "alert_risk", it) }
        check(tr("Nuovo permesso sensibile su app già installata"), Watch.toggle(this, "alert_perm")) { Watch.setToggle(this, "alert_perm", it) }
        check(tr("Avvisa anche per ogni nuova app installata da APK"), Watch.alertNewApk(this)) { Watch.setAlertNewApk(this, it) }
        check(tr("Firma di un'app cambiata"), Watch.toggle(this, "alert_sig")) { Watch.setToggle(this, "alert_sig", it) }
        section(tr("CESTINO"))
        check(tr("Sposta nel cestino invece di eliminare subito"), Trash.enabled(this)) { Trash.setEnabled(this, it) }
        val td = Trash.days(this)
        for (d in listOf(7, 30)) {
            button((if (d == td) "[x] " else "[ ] ") + trf("Svuota dopo %s giorni", d)) {
                Trash.setDays(this, d)
                showSettings()
            }
        }
        button(tr("[>] App fidate")) { showTrusted() }
        button(tr("[>] Storico avvisi")) { showHistory() }
        backButton()
    }

    private fun showTrusted() {
        token++
        val my = token
        screen = "trusted"
        box.removeAllViews()
        title(tr("APP_FIDATE"))
        text(
            tr("Per le app qui segnate non ricevi più avvisi (le app spia note vengono segnalate comunque). Sono elencate le app che in questo momento potrebbero far scattare un avviso."),
            size = 12f, color = grey
        )
        text(tr("Controllo…"), color = grey)
        work {
            val snap = try {
                Watch.snapshot(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (my == token) fillTrusted(snap) }
        }
    }

    private fun fillTrusted(snap: Snap?) {
        box.removeAllViews()
        title(tr("APP_FIDATE"))
        if (snap == null) {
            text(tr("Non sono riuscito a completare il controllo."), color = red)
            backButton()
            return
        }
        val trusted = Watch.trusted(this)
        val all = HashSet<String>()
        all.addAll(snap.acc); all.addAll(snap.admin); all.addAll(snap.listeners)
        all.addAll(snap.risk); all.addAll(snap.side)
        all.addAll(trusted.filter { it in snap.installed })
        all.removeAll(snap.spy)
        if (all.isEmpty()) {
            text(tr("Nessuna app da segnare: tutto tranquillo."), color = green)
        }
        for (p in all.sortedBy { (snap.labels[it] ?: it).lowercase() }) {
            val tags = ArrayList<String>()
            if (p in snap.acc) tags.add(tr("accessibilità"))
            if (p in snap.admin) tags.add("admin")
            if (p in snap.listeners) tags.add(tr("legge notifiche"))
            if (p in snap.risk) tags.add(tr("permessi sensibili"))
            if (p in snap.side && tags.isEmpty()) tags.add("APK")
            check((snap.labels[p] ?: p) + (if (tags.isEmpty()) "" else "\n" + tags.joinToString(", ")), p in trusted) {
                Watch.setTrusted(this, p, it)
            }
        }
        backButton()
    }

    private fun showHistory() {
        screen = "history"
        box.removeAllViews()
        title(tr("STORICO_AVVISI"))
        val list = Watch.history(this)
        if (list.isEmpty()) {
            text(tr("Nessun avviso finora."), color = grey)
        } else {
            text(trf("Ultimi %s avvisi, dal più recente.", list.size), size = 12f, color = grey)
            for (h in list) {
                text(
                    (if (h.high) "[!!] " else "[i] ") + DateFormat.format("dd/MM/yyyy HH:mm", Date(h.time)) +
                        "\n" + h.title + "\n" + h.text,
                    size = 13f, color = if (h.high) orange else dark, pad = 7
                )
                val pk = h.pkg
                if (pk != null) button(tr("[>] Apri la scheda dell'app")) { openApp(pk) }
            }
            button(tr("[>] Cancella lo storico")) {
                Watch.clearHistory(this)
                showHistory()
            }
        }
        backButton()
    }

    private fun offerCrashReport() {
        val rep = Crash.pending(this) ?: return
        try {
            AlertDialog.Builder(this)
                .setTitle(tr("L'app si è chiusa in modo imprevisto"))
                .setMessage(tr("Ho salvato un rapporto dell'errore nel telefono. Non è stato inviato da nessuna parte. Vuoi condividerlo (per esempio via e-mail) per aiutare a correggerlo?"))
                .setPositiveButton(tr("Condividi")) { _, _ ->
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_SUBJECT, "GhostALO - crash report")
                    i.putExtra(Intent.EXTRA_TEXT, rep)
                    try {
                        startActivity(Intent.createChooser(i, tr("Condividi")))
                    } catch (e: Exception) {
                        // niente da fare
                    }
                    Crash.clear(this)
                }
                .setNegativeButton(tr("Elimina")) { _, _ -> Crash.clear(this) }
                .setNeutralButton(tr("Più tardi"), null)
                .show()
        } catch (e: Exception) {
            // ignora
        }
    }

    // ---------- Pulizia e prestazioni ----------

    private fun showCleanHome() {
        screen = "cleanHome"
        box.removeAllViews()
        title(tr("PULIZIA_E_PRESTAZIONI"))
        text(
            tr("Cerca file inutili e mostra cosa occupa spazio. Prima di eliminare qualsiasi cosa vedi l'elenco e ti viene chiesta conferma. Servono due permessi speciali, che concedi tu dalle impostazioni di Android."),
            size = 13f, color = grey
        )

        val allFiles = Cleaner.hasAllFiles()
        text(
            if (allFiles) tr("[OK] Accesso ai file: concesso") else tr("[NO] Accesso ai file: non concesso"),
            color = if (allFiles) green else red, bold = true, pad = 10
        )
        if (!allFiles) {
            text(
                tr("Serve per trovare ed eliminare APK, file temporanei, miniature, cartelle vuote e duplicati."),
                size = 12f, color = grey
            )
            button(tr("[>] Concedi accesso a tutti i file")) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    openSettings(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
            }
        }

        val usage = Cleaner.hasUsageAccess(this)
        text(
            if (usage) tr("[OK] Statistiche di utilizzo: concesse") else tr("[NO] Statistiche di utilizzo: non concesse"),
            color = if (usage) green else red, bold = true, pad = 10
        )
        if (!usage) {
            text(
                tr("Serve per vedere la cache di ogni app e le app non usate da tempo."),
                size = 12f, color = grey
            )
            button(tr("[>] Concedi accesso alle statistiche")) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_USAGE_ACCESS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                }
            }
            text(
                tr("Se la pagina non si apre o l'interruttore non si accende, prova così:"),
                size = 12f, color = grey, pad = 8
            )
            button(tr("[>] Apri l'elenco di tutte le app")) {
                openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            text(
                tr("Nell'elenco cerca \"GhostALO\" e accendi l'interruttore. Se è grigio o dice \"impostazione con restrizioni\" (succede alle app installate da APK): apri la scheda dell'app qui sotto, tocca i tre puntini in alto a destra, scegli \"Consenti impostazioni con restrizioni\", poi torna all'elenco."),
                size = 12f, color = grey, pad = 4
            )
            button(tr("[>] Apri la scheda dell'app")) { openApp(packageName) }
        }

        if (!allFiles && !usage) {
            text(tr("Concedi almeno uno dei due permessi per iniziare."), color = grey, pad = 10)
        } else {
            button(tr("[>] Avvia scansione")) { startCleanScan() }
        }
        section(tr("CACHE DELLE ALTRE APP"))
        text(
            tr("Chiede ad Android di fare spazio: il sistema cancella da solo le cache delle app meno usate. Non serve nessun permesso. Libera quanto serve, non necessariamente tutto, e il risultato dipende dal telefono."),
            size = 12f, color = grey
        )
        button(tr("[>] Libera cache di sistema")) { startFreeCache() }
        section(tr("APPUNTI"))
        text(
            tr("Svuota il testo copiato (per esempio password o codici). Non cancella la cronologia della tastiera."),
            size = 12f, color = grey
        )
        button(tr("[>] Cancella gli appunti")) {
            try {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
                Toast.makeText(this, tr("Appunti cancellati."), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, tr("Impossibile cancellare gli appunti."), Toast.LENGTH_SHORT).show()
            }
        }
        backButton()
    }

    private fun startFreeCache() {
        token++
        val my = token
        screen = "cacheFree"
        box.removeAllViews()
        title(tr("LIBERA_CACHE"))
        text(tr("Sto chiedendo spazio ad Android… può servire qualche secondo."), color = grey)
        work {
            val r = Cleaner.freeSystemCache(this)
            runOnUiThread {
                if (my != token) return@runOnUiThread
                box.removeAllViews()
                title(tr("RISULTATO_CACHE"))
                if (!r.ok) {
                    text("[!!] " + r.message, color = orange, bold = true)
                    text(
                        tr("Puoi svuotare la cache a mano: tocca un'app nell'elenco di Pulizia, poi Archiviazione → Svuota cache."),
                        size = 12f, color = grey
                    )
                } else {
                    val gained = (r.freeAfter - r.freeBefore).coerceAtLeast(0L)
                    text(tr("Spazio liberato: ") + fmt(gained), size = 20f, color = green, bold = true, pad = 8)
                    text(tr("Spazio libero: ") + fmt(r.freeBefore) + " -> " + fmt(r.freeAfter), size = 13f)
                    if (r.cacheBefore != null && r.cacheAfter != null) {
                        text(
                            tr("Cache delle altre app: ") + fmt(r.cacheBefore) + " -> " + fmt(r.cacheAfter),
                            size = 13f
                        )
                    }
                    if (r.message.isNotEmpty()) text(r.message, color = grey, size = 12f)
                    if (gained < 1024L * 1024L && r.message.isEmpty()) {
                        text(
                            tr("Android non ha liberato nulla: su questo telefono il sistema non cancella le cache su richiesta. Usa la pulizia manuale dall'elenco in Pulizia."),
                            size = 12f, color = orange
                        )
                    }
                }
                button(tr("[>] Riprova")) { startFreeCache() }
                button(tr("[<] Pulizia e prestazioni")) { showCleanHome() }
                backButton()
            }
        }
    }

    private fun startCleanScan() {
        token++
        val my = token
        screen = "cleanScan"
        box.removeAllViews()
        title(tr("SCANSIONE_IN_CORSO"))
        text(tr("Può richiedere anche un minuto se hai molti file."), color = grey)
        work {
            val cats = if (Cleaner.hasAllFiles()) {
                try {
                    Cleaner.scanJunk()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val sizes = if (Cleaner.hasUsageAccess(this)) {
                try {
                    Cleaner.appSizes(this)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val unused = if (sizes != null) {
                try {
                    Cleaner.unusedApps(this, sizes)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            runOnUiThread {
                if (my == token) showCleanReport(cats, sizes, unused)
            }
        }
    }

    private fun showCleanReport(
        cats: List<JunkCategory>?,
        sizes: List<AppSize>?,
        unused: List<UnusedApp>?
    ) {
        screen = "cleanReport"
        box.removeAllViews()
        title(tr("RISULTATO_PULIZIA"))

        section(tr("FILE INUTILI"))
        if (cats == null) {
            text(tr("Accesso a tutti i file non concesso: controllo dei file saltato."), color = grey)
        } else if (cats.isEmpty()) {
            text(tr("[OK] Nessun file inutile trovato."), color = green)
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            text(
                tr("Spunta ciò che vuoi eliminare. Sotto ogni voce vedi alcuni esempi."),
                size = 12f, color = grey
            )
            for (c in cats) {
                val cb = CheckBox(this)
                cb.text = c.title + " - " + c.count + tr(" elementi, ") + fmt(c.bytes)
                cb.setTextColor(green)
                cb.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                cb.textSize = 14f
                cb.buttonTintList = ColorStateList.valueOf(green)
                cb.isChecked = c.checked
                cb.setOnCheckedChangeListener { _, v -> c.checked = v }
                box.addView(cb)
                text(c.hint, size = 11f, color = grey, pad = 2)
                val all = c.files + c.dirs
                val preview = all.take(5).joinToString("\n") { "  " + it.absolutePath.removePrefix(root) }
                val more = if (c.count > 5) tr("\n  ... e altri ") + (c.count - 5) else ""
                text(preview + more, size = 11f, color = grey, pad = 2)
            }
            button(tr("[>] Elimina selezionati")) { confirmDelete(cats) }
        }

        section(tr("CACHE DELLE APP"))
        if (sizes == null) {
            text(
                tr("Per vedere la cache delle app concedi l'accesso alle statistiche di utilizzo."),
                size = 13f, color = grey
            )
        } else {
            val totalCache = sizes.sumOf { it.cache }
            text(tr("Cache totale delle app: ") + fmt(totalCache), bold = true)
            text(
                tr("Android non permette ad altre app di svuotarla in automatico. Tocca un'app per aprire la sua scheda, poi Archiviazione → Svuota cache."),
                size = 12f, color = grey
            )
            for (a in sizes.sortedByDescending { it.cache }.take(15)) {
                val t = text(
                    a.label + " - cache " + fmt(a.cache) + tr(", totale ") + fmt(a.total),
                    size = 13f, pad = 7
                )
                t.setOnClickListener { openApp(a.pkg) }
            }
        }

        section(tr("APP NON USATE DA TEMPO"))
        if (unused == null) {
            text(tr("Serve l'accesso alle statistiche di utilizzo."), size = 13f, color = grey)
        } else if (unused.isEmpty()) {
            text(tr("[OK] Nessuna app inutilizzata da oltre 60 giorni."), color = green)
        } else {
            text(
                tr("Non risultano aperte da oltre 60 giorni. Disinstallarle libera spazio. Tocca per aprire la scheda dell'app."),
                size = 12f, color = grey
            )
            for (u in unused) {
                val since = if (u.daysAgo == null) tr("non usata da oltre 4 mesi") else tr("ultimo uso ") + u.daysAgo + tr(" giorni fa")
                val t = text(u.label + " - " + fmt(u.total) + " (" + since + ")", size = 13f, pad = 7)
                t.setOnClickListener { openApp(u.pkg) }
            }
        }

        section(tr("CRONOLOGIA BROWSER"))
        text(
            tr("Android non permette ad altre app di cancellare la cronologia dei browser. In Chrome: menu ⋮ → Elimina dati di navigazione. In Samsung Internet: menu → Impostazioni → Dati personali di navigazione → Elimina."),
            size = 12f, color = grey
        )

        section(tr("PER UN TELEFONO PIU VELOCE"))
        text(
            tr("- Tieni libero almeno il 10-15% dello spazio.\n- Disinstalla le app che non usi.\n- Riavvia ogni tanto e aggiorna sistema e app.\n- Diffida delle app \"booster RAM\": chiudere le app in background le fa solo ripartire e consuma più batteria."),
            size = 12f, color = grey
        )

        button(tr("[>] Ripeti scansione")) { startCleanScan() }
        backButton()
    }

    private fun confirmDelete(cats: List<JunkCategory>, again: () -> Unit = { startCleanScan() }) {
        val sel = cats.filter { it.checked }
        if (sel.isEmpty()) {
            Toast.makeText(this, tr("Nessuna voce selezionata."), Toast.LENGTH_SHORT).show()
            return
        }
        val n = sel.sumOf { it.count }
        val b = sel.sumOf { it.bytes }
        AlertDialog.Builder(this)
            .setTitle(tr("Conferma eliminazione"))
            .setMessage(
                if (Trash.active(this)) {
                    trf("%s elementi (", n) + fmt(b) + trf(") verranno spostati nel cestino e restano recuperabili per %s giorni.", Trash.days(this))
                } else {
                    trf("Verranno eliminati definitivamente %s elementi (", n) + fmt(b) +
                        tr("). L'operazione non si può annullare.")
                }
            )
            .setPositiveButton(tr("Elimina")) { _, _ -> doDelete(sel, again) }
            .setNegativeButton(tr("Annulla"), null)
            .show()
    }

    private fun doDelete(sel: List<JunkCategory>, again: () -> Unit = { startCleanScan() }) {
        token++
        val my = token
        screen = "cleanDelete"
        box.removeAllViews()
        title(tr("ELIMINAZIONE"))
        text(tr("Eliminazione in corso…"), size = 18f, bold = true)
        work {
            val res = Cleaner.delete(this, sel)
            runOnUiThread {
                if (my == token) {
                    box.removeAllViews()
                    title(tr("PULIZIA_COMPLETATA"))
                    if (Trash.active(this)) {
                        text(tr("Spostati nel cestino: ") + fmt(res.first), size = 20f, color = green, bold = true, pad = 8)
                        text(tr("Lo spazio si libera quando il cestino viene svuotato (da Pulizia > Cestino o in automatico)."), size = 12f, color = grey)
                    } else {
                        text(tr("Spazio liberato: ") + fmt(res.first), size = 20f, color = green, bold = true, pad = 8)
                    }
                    if (res.second > 0) {
                        text(res.second.toString() + tr(" file non è stato possibile eliminarli."), color = orange)
                    }
                    button(tr("[>] Nuova scansione")) { again() }
                    backButton()
                }
            }
        }
    }

    // ---------- Pulizia WhatsApp e Telegram ----------

    private var chatDays = 90

    private fun allFilesButton() {
        button(tr("[>] Concedi accesso a tutti i file")) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                openSettings(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        }
    }

    private fun showChatHome() {
        screen = "chatHome"
        box.removeAllViews()
        title(tr("PULIZIA_CHAT"))
        text(
            tr("Trova in WhatsApp, WhatsApp Business e Telegram i media vecchi, le note vocali, le copie dei file inviati e gli stati già visti. Vedi l'elenco e confermi tu prima di eliminare."),
            size = 13f, color = grey
        )
        text(
            tr("Le chat e i messaggi non vengono toccati: se elimini un media, nella chat resta il segnaposto ma il file non è più nel telefono. Su WhatsApp si riscarica solo se è ancora sui server o in un backup; su Telegram si riscarica dal cloud (tranne le chat segrete)."),
            size = 12f, color = orange
        )
        val ok = Cleaner.hasAllFiles()
        text(
            if (ok) tr("[OK] Accesso ai file: concesso") else tr("[NO] Accesso ai file: non concesso"),
            color = if (ok) green else red, bold = true, pad = 10
        )
        if (!ok) {
            text(tr("Serve per leggere le cartelle dei media delle chat."), size = 12f, color = grey)
            allFilesButton()
        } else {
            text(tr("Considera \"vecchi\" i file più vecchi di:"), size = 13f, pad = 10)
            for (d in listOf(30, 90, 180, 365)) {
                val label = if (d == chatDays) trf("[x] %s giorni", d) else trf("[ ] %s giorni", d)
                button(label) {
                    chatDays = d
                    showChatHome()
                }
            }
            button(tr("[>] Avvia scansione")) { startChatScan() }
        }
        backButton()
    }

    private fun startChatScan() {
        token++
        val my = token
        screen = "chatScan"
        box.removeAllViews()
        title(tr("SCANSIONE_CHAT"))
        text(tr("Sto cercando i media delle chat…"), color = grey)
        val days = chatDays
        work {
            val rep = try {
                ChatCleaner.scan(days)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) showChatReport(rep)
            }
        }
    }

    private fun showChatReport(rep: ChatReport?) {
        screen = "chatReport"
        box.removeAllViews()
        title(tr("RISULTATO_CHAT"))
        if (rep == null) {
            text(tr("Non sono riuscito a leggere le cartelle."), color = red)
        } else if (rep.found.isEmpty()) {
            text(
                tr("Non ho trovato cartelle di WhatsApp o Telegram su questo telefono."),
                color = grey
            )
        } else {
            section(tr("SPAZIO OCCUPATO"))
            for (app in rep.found) {
                text(app + ": " + fmt(rep.totals[app] ?: 0L) + tr(" di media"), bold = true)
            }
            section(tr("DA ELIMINARE"))
            if (rep.cats.isEmpty()) {
                text(tr("[OK] Niente di più vecchio di ") + rep.days + tr(" giorni."), color = green)
            } else {
                val root = Environment.getExternalStorageDirectory().absolutePath
                text(
                    tr("Spunta ciò che vuoi eliminare. Le voci più delicate sono già senza spunta."),
                    size = 12f, color = grey
                )
                for (c in rep.cats) {
                    val cb = CheckBox(this)
                    cb.text = c.title + " - " + c.count + tr(" file, ") + fmt(c.bytes)
                    cb.setTextColor(green)
                    cb.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    cb.textSize = 14f
                    cb.buttonTintList = ColorStateList.valueOf(green)
                    cb.isChecked = c.checked
                    cb.setOnCheckedChangeListener { _, v -> c.checked = v }
                    box.addView(cb)
                    text(c.hint, size = 11f, color = grey, pad = 2)
                    val preview = c.files.take(3).joinToString("\n") {
                        "  " + it.absolutePath.removePrefix(root)
                    }
                    val more = if (c.count > 3) tr("\n  ... e altri ") + (c.count - 3) else ""
                    text(preview + more, size = 11f, color = grey, pad = 2)
                }
                button(tr("[>] Elimina selezionati")) {
                    confirmDelete(rep.cats) { startChatScan() }
                }
            }
            text(
                tr("Non posso svuotare la cache interna di queste app (Android la protegge): per quella usa Impostazioni → App → Archiviazione → Svuota cache."),
                size = 11f, color = grey, pad = 10
            )
        }
        button(tr("[>] Ripeti scansione")) { startChatScan() }
        backButton()
    }

    // ---------- Permessi: chi usa cosa ----------

    private var permData: Map<String, List<Pair<String, String>>> = emptyMap()

    private val permGroups = linkedMapOf(
        tr("Fotocamera") to listOf(Manifest.permission.CAMERA),
        tr("Microfono") to listOf(Manifest.permission.RECORD_AUDIO),
        tr("Posizione") to listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        ),
        tr("Posizione in background") to listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        tr("Contatti") to listOf(Manifest.permission.READ_CONTACTS),
        "SMS" to listOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS
        ),
        tr("Registro chiamate") to listOf(Manifest.permission.READ_CALL_LOG),
        tr("Calendario") to listOf(Manifest.permission.READ_CALENDAR),
        tr("Foto e video") to listOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO
        ),
        "Audio" to listOf(Manifest.permission.READ_MEDIA_AUDIO)
    )

    private fun startPermScan() {
        token++
        val my = token
        screen = "permScan"
        box.removeAllViews()
        title(tr("PERMESSI"))
        text(tr("Controllo le app installate…"), color = grey)
        work {
            val res = LinkedHashMap<String, ArrayList<Pair<String, String>>>()
            for (g in permGroups.keys) res[g] = ArrayList()
            try {
                val pm = packageManager
                val flags = PackageManager.GET_PERMISSIONS
                val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(flags)
                }
                for (p in pkgs) {
                    val ai = p.applicationInfo ?: continue
                    if (isSystem(ai) || p.packageName == packageName) continue
                    val g = grantedPerms(p)
                    val label = pm.getApplicationLabel(ai).toString()
                    for ((name, perms) in permGroups) {
                        if (perms.any { it in g }) res[name]!!.add(Pair(label, p.packageName))
                    }
                }
            } catch (e: Exception) {
                // elenco parziale
            }
            for (l in res.values) l.sortBy { it.first.lowercase() }
            runOnUiThread {
                if (my == token) {
                    permData = res
                    showPermHome()
                }
            }
        }
    }

    private fun showPermHome() {
        screen = "permHome"
        box.removeAllViews()
        title(tr("PERMESSI_CHI_USA_COSA"))
        text(
            tr("Per ogni permesso vedi quali app installate da te lo hanno ottenuto. Tocca una voce, poi un'app: si apre la sua scheda, dove puoi togliere il permesso (Autorizzazioni). Questa app non può revocare i permessi delle altre, lo decidi tu."),
            size = 12f, color = grey
        )
        for ((name, list) in permData) {
            val risky = name == tr("Posizione in background") || name == "SMS" ||
                name == tr("Registro chiamate") || name == tr("Microfono") || name == tr("Fotocamera")
            val color = if (list.isNotEmpty() && risky) orange else green
            button("[" + list.size + "] " + name) { showPermGroup(name) }
        }
        section(tr("REGISTRO DI USO"))
        text(
            tr("Quando un'app ha usato fotocamera, microfono o posizione lo sa solo Android: dal 12 in poi c'è un registro nelle impostazioni, che questa app non può leggere."),
            size = 12f, color = grey
        )
        button(tr("[>] Registro uso permessi di Android")) {
            openSettings("android.intent.action.REVIEW_PERMISSION_USAGE")
        }
        button(tr("[>] Gestione permessi di Android")) {
            openSettings("android.intent.action.MANAGE_PERMISSIONS")
        }
        button(tr("[>] Ripeti controllo")) { startPermScan() }
        backButton()
    }

    private fun showPermGroup(name: String) {
        screen = "permGroup"
        box.removeAllViews()
        title(name.uppercase().replace(' ', '_'))
        val list = permData[name] ?: emptyList()
        if (list.isEmpty()) {
            text(tr("[OK] Nessuna app installata da te ha questo permesso."), color = green)
        } else {
            text(tr("Tocca un'app per aprire la sua scheda."), size = 12f, color = grey)
            for ((label, pkg) in list) {
                val t = text(label + "\n     " + pkg, size = 13f, pad = 7)
                t.setOnClickListener { openApp(pkg) }
            }
        }
        button(tr("[<] Torna ai permessi")) { showPermHome() }
        backButton()
    }

    // ---------- Controllo degli APK scaricati ----------

    private fun showApkHome() {
        screen = "apkHome"
        box.removeAllViews()
        title(tr("CONTROLLO_APK"))
        text(
            tr("Cerca i file .apk nel telefono e li controlla prima che tu li installi: app spia note, firma diversa da quella dell'app già installata (possibile falso) e permessi sensibili richiesti. Non installa né elimina niente."),
            size = 13f, color = grey
        )
        val ok = Cleaner.hasAllFiles()
        text(
            if (ok) tr("[OK] Accesso ai file: concesso") else tr("[NO] Accesso ai file: non concesso"),
            color = if (ok) green else red, bold = true, pad = 10
        )
        if (!ok) {
            allFilesButton()
        } else {
            button(tr("[>] Avvia controllo")) { startApkScan() }
        }
        backButton()
    }

    private fun startApkScan() {
        token++
        val my = token
        screen = "apkScan"
        box.removeAllViews()
        title(tr("CONTROLLO_IN_CORSO"))
        text(tr("Cerco i file .apk…"), color = grey)
        work {
            val res = try {
                ApkCheck.scan(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) showApkReport(res)
            }
        }
    }

    private fun showApkReport(list: List<ApkInfo>?) {
        screen = "apkReport"
        box.removeAllViews()
        title(tr("RISULTATO_APK"))
        if (list == null) {
            text(tr("Non sono riuscito a completare il controllo."), color = red)
        } else if (list.isEmpty()) {
            text(tr("[OK] Nessun file .apk trovato."), color = green)
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            text(list.size.toString() + tr(" file trovati."), bold = true)
            for (a in list) {
                val tags = ArrayList<String>()
                if (!a.readable) tags.add(tr("file non leggibile o incompleto"))
                if (a.spy) tags.add(tr("APP SPIA NOTA: non installarla"))
                if (a.certDiffers) tags.add(tr("firma DIVERSA dall'app già installata: potrebbe essere un falso"))
                else if (a.installed) tags.add(tr("già installata, stessa firma"))
                if (a.sensitive.isNotEmpty()) tags.add(tr("chiede: ") + a.sensitive.joinToString(", "))
                if (a.readable && tags.isEmpty()) tags.add(tr("nessun problema evidente (") + a.permCount + tr(" permessi richiesti)"))
                val tag = when (a.level) {
                    0 -> "[OK] "
                    2 -> "[!!] "
                    else -> "[XX] "
                }
                val color = when (a.level) {
                    0 -> dark
                    2 -> orange
                    else -> red
                }
                text(
                    tag + a.label + "\n     " + a.file.absolutePath.removePrefix(root) + "\n     " +
                        tags.joinToString("; ") + " - " + fmt(a.file.length()),
                    size = 12f, color = color, pad = 7
                )
            }
            text(
                tr("Un APK senza segnalazioni non è garantito sicuro: scarica app solo da fonti che conosci. Per eliminare i vecchi APK usa [4] Pulizia."),
                size = 11f, color = grey, pad = 8
            )
        }
        button(tr("[>] Ripeti controllo")) { startApkScan() }
        backButton()
    }

    // ---------- Rete Wi-Fi e reti pubbliche ----------

    private fun showNet() {
        screen = "net"
        box.removeAllViews()
        title(tr("RETE_E_RETI_PUBBLICHE"))
        text(
            tr("Controlla la rete a cui sei collegato usando solo le impostazioni di Android. Non invia né riceve dati, quindi non può scoprire attacchi man-in-the-middle: per quelli servirebbe analizzare il traffico di rete."),
            size = 12f, color = grey
        )
        for (c in NetCheck.run(this)) {
            val tag = when (c.level) {
                0 -> "[OK]"
                1 -> "[--]"
                2 -> "[!!]"
                else -> "[XX]"
            }
            val color = when (c.level) {
                0 -> dark
                1 -> grey
                2 -> orange
                else -> red
            }
            val t = text(tag + " " + c.title + "\n     " + c.detail, size = 13f, color = color, pad = 7)
            val act = c.action
            if (act != null) t.setOnClickListener { openSettings(act) }
        }
        section(tr("CONSIGLI PER LE RETI PUBBLICHE"))
        text(
            tr("- Evita banca e acquisti su Wi-Fi aperti: usa i dati mobili.\n- Disattiva il collegamento automatico alle reti aperte.\n- Controlla il lucchetto e il nome esatto della rete: i falsi hotspot copiano i nomi dei locali.\n- Usa siti e app con HTTPS e tieni attivo il DNS privato.\n- Se usi una VPN, attiva \"sempre attiva\" e \"blocca connessioni senza VPN\"."),
            size = 12f, color = grey
        )
        section(tr("BLOCCARE PUBBLICITA E TRACKER"))
        text(
            tr("Senza installare altre app puoi usare un DNS che li filtra: Impostazioni → Rete e Internet → DNS privato → \"Nome host del provider\" e scrivi, per esempio, dns.adguard-dns.com (AdGuard) o adblock.dns.mullvad.net (Mullvad). Verifica il nome sul sito del fornitore. Il fornitore del DNS vede i nomi dei siti che visiti: scegli uno di cui ti fidi."),
            size = 12f, color = grey
        )
        button(tr("[>] Impostazioni Wi-Fi")) { openSettings(Settings.ACTION_WIFI_SETTINGS) }
        button(tr("[>] Ripeti controllo")) { showNet() }
        backButton()
    }

    // ---------- Spazio privato e profili ----------

    private fun showProfiles() {
        screen = "profiles"
        box.removeAllViews()
        title(tr("SPAZIO_PRIVATO_E_PROFILI"))
        text(
            tr("Android può tenere app e dati separati in un profilo a parte: Spazio privato (Android 15 e successivi), profilo di lavoro (usato anche da Shelter e Island) o app clonate. Questa app non crea profili: ti dice se ce ne sono e ti porta alle impostazioni."),
            size = 13f, color = grey
        )
        section(tr("PROFILI TROVATI"))
        val um = getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val n = try {
            um.userProfiles.size
        } catch (e: Exception) {
            1
        }
        if (n <= 1) {
            text(tr("[--] Nessun profilo aggiuntivo visibile."), color = grey)
            text(
                tr("Uno Spazio privato bloccato non è visibile alle app: potrebbe esistere anche se qui non compare."),
                size = 12f, color = grey
            )
        } else {
            text(
                tr("[!!] Oltre al profilo principale ce n'è ") + (n - 1) + tr(" altro/i (lavoro, clone o spazio privato)."),
                color = orange, bold = true
            )
        }
        text(
            tr("Attenzione: questa app controlla solo le app del profilo principale. Le app dentro un altro profilo non vengono analizzate, quindi un'app spia lì dentro non verrebbe trovata."),
            size = 12f, color = orange, pad = 8
        )
        section(tr("SPAZIO PRIVATO"))
        if (Build.VERSION.SDK_INT >= 35) {
            text(
                tr("Il tuo Android lo supporta. Si attiva da Impostazioni → Sicurezza e privacy → Spazio privato: si blocca con un PIN e le sue app non compaiono nel menu né nelle notifiche quando è bloccato."),
                size = 13f
            )
            button(tr("[>] Apri Spazio privato")) { openSettings("android.settings.PRIVATE_SPACE_SETTINGS") }
        } else {
            text(
                tr("Il tuo Android (") + Build.VERSION.RELEASE + tr(") non ha lo Spazio privato: arriva con Android 15. In alternativa esistono app come Shelter o Island, che usano il profilo di lavoro."),
                size = 13f
            )
        }
        button(tr("[>] Impostazioni di sicurezza")) { openSettings(Settings.ACTION_SECURITY_SETTINGS) }
        backButton()
    }

    // ---------- App senza icona e cartelle nascoste ----------

    private fun showHiddenHome() {
        screen = "hiddenHome"
        box.removeAllViews()
        title(tr("APP_E_CARTELLE_NASCOSTE"))
        text(
            tr("Cerca le app installate senza icona nel menu e le cartelle nascoste che contengono foto o video. Sono indizi, non prove: molte app senza icona sono componenti normali. Non modifica niente."),
            size = 13f, color = grey
        )
        val ok = Cleaner.hasAllFiles()
        text(
            if (ok) tr("[OK] Accesso ai file: concesso") else tr("[NO] Accesso ai file: non concesso"),
            color = if (ok) green else red, bold = true, pad = 10
        )
        if (!ok) {
            text(
                tr("Senza questo permesso controllo solo le app, non le cartelle."),
                size = 12f, color = grey
            )
            allFilesButton()
        }
        button(tr("[>] Avvia controllo")) { startHiddenScan() }
        backButton()
    }

    private fun startHiddenScan() {
        token++
        val my = token
        screen = "hiddenScan"
        box.removeAllViews()
        title(tr("CONTROLLO_IN_CORSO"))
        text(tr("Può richiedere anche un minuto."), color = grey)
        work {
            val rep = try {
                HiddenScan.scan(this, Cleaner.hasAllFiles())
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) showHiddenReport(rep)
            }
        }
    }

    private fun showHiddenReport(r: HiddenReport?) {
        screen = "hiddenReport"
        box.removeAllViews()
        title(tr("RISULTATO_NASCOSTI"))
        if (r == null) {
            text(tr("Non sono riuscito a completare il controllo."), color = red)
            button(tr("[>] Riprova")) { startHiddenScan() }
            backButton()
            return
        }

        section(tr("APP SENZA ICONA"))
        if (r.apps.isEmpty()) {
            text(tr("[OK] Nessuna app senza icona che meriti attenzione."), color = green)
        } else {
            text(
                tr("Tocca un'app per aprire la sua scheda. Se non la riconosci, controlla da dove viene e valuta di disinstallarla."),
                size = 12f, color = grey
            )
            for (a in r.apps) {
                val tags = ArrayList<String>()
                if (a.spy) tags.add(tr("APP SPIA NOTA"))
                tags.addAll(a.special)
                if (a.sideloaded) tags.add(tr("installata da APK"))
                if (a.sensitive.isNotEmpty()) tags.add(tr("accede a: ") + a.sensitive.joinToString(", "))
                val strong = a.spy || a.special.isNotEmpty()
                val t = text(
                    (if (strong) "[!!] " else "[?]  ") + a.label + "\n     " + a.pkg + "\n     " +
                        tags.joinToString("; "),
                    size = 13f, color = if (a.spy) red else if (strong) orange else dark, pad = 7
                )
                t.setOnClickListener { openApp(a.pkg) }
            }
        }
        if (r.normalCount > 0) {
            text(
                r.normalCount.toString() + tr(" altre app senza icona, installate da uno store e senza accessi particolari: probabilmente componenti normali."),
                size = 11f, color = grey, pad = 8
            )
        }

        section(tr("CARTELLE NASCOSTE CON FOTO/VIDEO"))
        if (r.folders == null) {
            text(tr("Controllo saltato: manca l'accesso a tutti i file."), size = 13f, color = grey)
        } else if (r.folders.isEmpty()) {
            text(tr("[OK] Nessuna cartella nascosta con foto o video."), color = green)
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            text(
                tr("Cartelle che la galleria non mostra. Alcune sono normali (cestino della galleria, dati di app di foto o di chat). Aprile con un'app di gestione file che mostri i file nascosti."),
                size = 12f, color = grey
            )
            for (f in r.folders) {
                val ex = f.sample.joinToString("\n") { "     es. " + it.name }
                text(
                    f.dir.absolutePath.removePrefix(root) + "\n     " + f.count + tr(" file, ") +
                        fmt(f.bytes) + " - " + f.reason + "\n" + ex,
                    size = 12f, pad = 7
                )
            }
        }
        if (r.truncated) {
            text(tr("Molti file: il controllo si è fermato prima della fine."), size = 11f, color = orange)
        }
        text(
            tr("Un risultato pulito non garantisce che non ci sia nulla: le app ben nascoste (per esempio con accesso root) sfuggono a qualsiasi app normale."),
            size = 11f, color = grey, pad = 10
        )
        button(tr("[>] Ripeti controllo")) { startHiddenScan() }
        backButton()
    }

    // ---------- App attive in background ----------

    private var bgHours = 24

    private fun dur(ms: Long): String {
        val m = ms / 60000L
        return if (m >= 60L) (m / 60L).toString() + " h " + (m % 60L) + " min" else "$m min"
    }

    private fun showBgHome() {
        screen = "bgHome"
        box.removeAllViews()
        title(tr("APP_IN_BACKGROUND"))
        text(
            tr("Mostra quali app hanno tenuto attivo un servizio in primo piano (quello con la notifica fissa) e per quanto tempo, confrontato con quanto le hai usate davvero. Android non permette ad altre app di misurare la batteria per app né di chiudere i processi in modo utile: decidi tu, app per app."),
            size = 13f, color = grey
        )
        val usage = Cleaner.hasUsageAccess(this)
        text(
            if (usage) tr("[OK] Statistiche di utilizzo: concesse") else tr("[NO] Statistiche di utilizzo: non concesse"),
            color = if (usage) green else red, bold = true, pad = 10
        )
        if (!usage) {
            button(tr("[>] Concedi accesso alle statistiche")) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                }
            }
            button(tr("[>] Apri l'elenco di tutte le app")) { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            text(
                tr("Se l'interruttore è grigio: scheda dell'app → tre puntini → \"Consenti impostazioni con restrizioni\"."),
                size = 12f, color = grey
            )
            button(tr("[>] Apri la scheda dell'app")) { openApp(packageName) }
        } else {
            text(tr("Periodo da controllare:"), size = 13f, pad = 8)
            for (h in listOf(24, 72)) {
                button((if (h == bgHours) "[x] " else "[ ] ") + trf("ultime %s ore", h)) {
                    bgHours = h
                    showBgHome()
                }
            }
            button(tr("[>] Avvia controllo")) { startBgScan() }
        }
        backButton()
    }

    private fun startBgScan() {
        token++
        val my = token
        screen = "bgScan"
        box.removeAllViews()
        title(tr("CONTROLLO_IN_CORSO"))
        text(tr("Leggo gli eventi di sistema…"), color = grey)
        val h = bgHours
        work {
            val res = try {
                Background.scan(this, h)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) showBgReport(res, h)
            }
        }
    }

    private fun showBgReport(list: List<BgApp>?, hours: Int) {
        screen = "bgReport"
        box.removeAllViews()
        title(tr("RISULTATO_BACKGROUND"))
        if (list == null) {
            text(tr("Non sono riuscito a leggere gli eventi di sistema."), color = red)
        } else if (list.isEmpty()) {
            text(trf("[OK] Nelle ultime %s ore nessuna app installata da te ha tenuto attivo un servizio a lungo.", hours), color = green)
        } else {
            text(
                trf("Nelle ultime %s ore, app che hanno tenuto un servizio attivo. Tocca un'app per aprire la sua scheda: lì puoi usare Batteria → Limitata, oppure Arresto forzato.", hours),
                size = 12f, color = grey
            )
            for (a in list) {
                val tag = if (a.wasteful) "[!!] " else "     "
                val boot = if (a.boot) tr(", parte all'accensione") else ""
                val t = text(
                    tag + a.label + tr("\n     servizio attivo ") + dur(a.serviceMillis) + " (" + a.starts +
                        tr(" avvii), usata ") + dur(a.screenMillis) + boot,
                    size = 13f, color = if (a.wasteful) orange else dark, pad = 7
                )
                t.setOnClickListener { openApp(a.pkg) }
            }
            text(
                tr("[!!] = oltre 1 ora in background ma usata meno di 10 minuti: la prima da limitare o disinstallare se non ti serve. Alcuni servizi sono normali (musica, navigatore, sincronizzazione)."),
                size = 11f, color = grey, pad = 8
            )
        }
        section(tr("BATTERIA"))
        text(
            tr("Il consumo preciso per app lo mostra solo Android, nelle sue impostazioni."),
            size = 12f, color = grey
        )
        button(tr("[>] Consumo batteria per app")) { openSettings(Intent.ACTION_POWER_USAGE_SUMMARY) }
        button(tr("[>] Ottimizzazione batteria")) {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        button(tr("[>] Ripeti controllo")) { startBgScan() }
        backButton()
    }

    // ---------- Analisi dello spazio per tipo ----------

    private fun bar(part: Long, whole: Long, width: Int = 28): String {
        val f = if (whole <= 0L) 0.0 else part.toDouble() / whole.toDouble()
        val n = Math.round(f.coerceIn(0.0, 1.0) * width).toInt()
        val pct = Math.round(f * 100.0).toInt()
        return "[#".repeat(n) + ".".repeat(width - n) + "] " + pct + "%"
    }

    private fun showSpaceHome() {
        screen = "spaceHome"
        box.removeAllViews()
        title(tr("ANALISI_SPAZIO"))
        text(
            tr("Mostra cosa occupa lo spazio: foto, video, audio, documenti, archivi e app, con i file più grandi. Non modifica niente."),
            size = 13f, color = grey
        )
        val ok = Cleaner.hasAllFiles()
        text(
            if (ok) tr("[OK] Accesso ai file: concesso") else tr("[NO] Accesso ai file: non concesso"),
            color = if (ok) green else red, bold = true, pad = 10
        )
        if (!ok) {
            text(tr("Serve per misurare i file presenti nel telefono."), size = 12f, color = grey)
            allFilesButton()
        } else {
            if (!Cleaner.hasUsageAccess(this)) {
                text(
                    tr("Facoltativo: concedi anche l'accesso alle statistiche di utilizzo (vedi [4] Pulizia) per includere lo spazio delle app."),
                    size = 12f, color = grey
                )
            }
            button(tr("[>] Avvia analisi")) { startSpaceScan() }
        }
        backButton()
    }

    private fun startSpaceScan() {
        token++
        val my = token
        screen = "spaceScan"
        box.removeAllViews()
        title(tr("ANALISI_IN_CORSO"))
        text(tr("Può richiedere anche un minuto se hai molti file."), color = grey)
        work {
            val rep = try {
                StorageAnalyzer.analyze(this)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) showSpaceReport(rep)
            }
        }
    }

    private fun showSpaceReport(r: SpaceReport?) {
        screen = "spaceReport"
        box.removeAllViews()
        title(tr("SPAZIO_PER_TIPO"))
        if (r == null) {
            text(tr("Non sono riuscito a leggere i file."), color = red)
            button(tr("[>] Riprova")) { startSpaceScan() }
            backButton()
            return
        }
        val used = (r.total - r.free).coerceAtLeast(0L)
        text(tr("Memoria: ") + fmt(used) + tr(" usati su ") + fmt(r.total), bold = true, pad = 6)
        text(bar(used, r.total), color = if (used > r.total * 0.9) red else green, pad = 2)
        text(tr("Liberi: ") + fmt(r.free), color = grey, pad = 2)

        section(tr("PER TIPO"))
        var counted = 0L
        for (t in r.types) {
            counted += t.bytes
            text(t.label.padEnd(14) + fmt(t.bytes) + "  (" + t.count + tr(" file)"), size = 13f, bold = true, pad = 2)
            text(bar(t.bytes, r.total), size = 13f, pad = 3)
        }
        if (r.apps != null) {
            counted += r.apps
            text(tr("App e dati").padEnd(14) + fmt(r.apps), size = 13f, bold = true, pad = 2)
            text(bar(r.apps, r.total), size = 13f, pad = 3)
        }
        val rest = used - counted
        if (rest > 0L) {
            text(tr("Sistema e altro").padEnd(14) + fmt(rest), size = 13f, bold = true, pad = 2)
            text(bar(rest, r.total), size = 13f, color = grey, pad = 3)
        }
        if (r.apps == null) {
            text(
                tr("Le app non sono incluse: manca l'accesso alle statistiche di utilizzo."),
                size = 11f, color = grey, pad = 6
            )
        }
        text(
            tr("Valori indicativi: i dati privati delle app e il sistema non sono misurabili con precisione, e i file possono essere contati anche nelle app."),
            size = 11f, color = grey, pad = 6
        )
        if (r.truncated) {
            text(tr("Molti file: l'analisi si è fermata prima della fine."), size = 11f, color = orange)
        }

        section(tr("FILE PIU GRANDI"))
        if (r.big.isEmpty()) {
            text(tr("Nessun file sopra i 20 MB."), color = grey)
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            for ((f, len) in r.big) {
                text(fmt(len) + "  " + f.absolutePath.removePrefix(root), size = 12f, pad = 3)
            }
        }
        button(tr("[>] Ripeti analisi")) { startSpaceScan() }
        backButton()
    }

    // ---------- Scansione privacy: logica ----------

    private fun isSystem(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun nameOf(pkg: String): Pair<String, Boolean> {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            Pair(packageManager.getApplicationLabel(ai).toString(), isSystem(ai))
        } catch (e: Exception) {
            Pair(pkg, false)
        }
    }

    private fun grantedPerms(p: PackageInfo): Set<String> {
        val req = p.requestedPermissions ?: return emptySet()
        val fl = p.requestedPermissionsFlags ?: return emptySet()
        val out = HashSet<String>()
        for (i in req.indices) {
            if (i < fl.size && (fl[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                out.add(req[i])
            }
        }
        return out
    }

    private fun scan(): Report {
        val pm = packageManager
        val (spyP, spyC) = Stalker.load(this)

        // Servizi di accessibilità attivi
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accPk = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()

        // Amministratori del dispositivo
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins = dpm.activeAdmins
        val adminPk = HashSet<String>()
        if (admins != null) {
            for (c in admins) adminPk.add(c.packageName)
        }

        // Lettori di notifiche
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val nlPk = nl.split(":")
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
            .toSet()

        // Tastiere
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imePk = imm.enabledInputMethodList.map { it.packageName }.toSet()
        val defIme = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "")
            .substringBefore("/")

        // App installate (con permessi e firme)
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }

        val flagged = ArrayList<AppFinding>()
        val spyHits = ArrayList<String>()
        val cam = ArrayList<String>()
        val mic = ArrayList<String>()
        var quiet = 0

        for (p in pkgs) {
            val ai = p.applicationInfo ?: continue
            if (p.packageName == packageName) continue

            val sys = isSystem(ai)
            val byPkg = p.packageName in spyP
            var byCert = false
            if (!sys && !byPkg && spyC.isNotEmpty()) {
                byCert = Stalker.certHashes(p).any { it in spyC }
            }
            if (sys && !byPkg) continue

            val label = pm.getApplicationLabel(ai).toString()
            val granted = grantedPerms(p)
            val reasons = ArrayList<String>()
            var score = 0

            if (byPkg) {
                score += 10
                reasons.add(tr("APP SPIA NOTA: il pacchetto è nell'elenco pubblico di stalkerware"))
                spyHits.add(label + " (" + p.packageName + ")")
            }
            if (byCert) {
                score += 10
                reasons.add(tr("APP SPIA NOTA: firmata con una chiave usata da stalkerware noti"))
                spyHits.add(label + " (" + p.packageName + ")")
            }
            if (p.packageName in accPk) {
                score += 4
                reasons.add(tr("Servizio di accessibilità attivo (può leggere lo schermo e ciò che scrivi)"))
            }
            if (p.packageName in adminPk) {
                score += 4
                reasons.add(tr("Amministratore del dispositivo (difficile da rimuovere)"))
            }
            if (p.packageName in nlPk) {
                score += 2
                reasons.add(tr("Può leggere tutte le notifiche"))
            }
            if (p.packageName in imePk) {
                score += 1
                reasons.add(tr("Tastiera di terze parti (vede ciò che scrivi)"))
            }
            if (Manifest.permission.CAMERA in granted) {
                score += 1
                reasons.add(tr("Accesso alla fotocamera"))
                if (!sys) cam.add(label)
            }
            if (Manifest.permission.RECORD_AUDIO in granted) {
                score += 1
                reasons.add(tr("Accesso al microfono"))
                if (!sys) mic.add(label)
            }
            if (Manifest.permission.READ_SMS in granted) {
                score += 2
                reasons.add(tr("Può leggere gli SMS"))
            }
            if (Manifest.permission.READ_CALL_LOG in granted) {
                score += 2
                reasons.add(tr("Può leggere il registro chiamate"))
            }
            val installer = try {
                pm.getInstallSourceInfo(p.packageName).installingPackageName
            } catch (e: Exception) {
                null
            }
            if (!sys && (installer == null || installer !in storeInstallers)) {
                score += 2
                reasons.add(tr("Installata fuori da un negozio ufficiale (APK manuale)"))
            }

            if (score >= 3) {
                flagged.add(AppFinding(p.packageName, label, score, reasons))
            } else {
                quiet++
            }
        }
        flagged.sortByDescending { it.score }
        cam.sort()
        mic.sort()

        val accLines = accPk.map {
            val (n, sys) = nameOf(it)
            if (sys) trf("%s (app di sistema)", n) else trf("%s (app installata da te o da altri)", n)
        }
        val keyboardLines = imePk.map {
            val (n, sys) = nameOf(it)
            val kind = if (sys) tr("di sistema") else tr("di terze parti")
            val def = if (it == defIme) tr(", in uso") else ""
            "$n ($kind$def)"
        }

        return Report(
            flagged = flagged,
            quietCount = quiet,
            camApps = cam,
            micApps = mic,
            accLines = accLines,
            keyboardLines = keyboardLines,
            photoLines = scanPhotos(),
            bigFiles = bigFiles(),
            storageLine = storageLine(),
            spyHits = spyHits.distinct(),
            stalkerDbSize = spyP.size + spyC.size
        )
    }

    private fun hasMedia(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanPhotos(): List<String>? {
        if (!hasMedia()) return null
        val camPk = try {
            packageManager.queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (e: Exception) {
            emptySet<String>()
        }
        val counts = HashMap<String, Int>()
        val last = HashMap<String, Long>()
        try {
            val proj = arrayOf(
                MediaStore.Images.Media.OWNER_PACKAGE_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            if (cursor != null) {
                cursor.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 20000) {
                        n++
                        val owner = c.getString(0)
                        if (owner == null) continue
                        val lower = owner.lowercase()
                        if (owner in camPk || lower.contains("camera") ||
                            lower.contains("systemui") || lower.contains("gallery") ||
                            lower.contains("photos")
                        ) continue
                        counts[owner] = (counts[owner] ?: 0) + 1
                        if (!last.containsKey(owner)) last[owner] = c.getLong(1)
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map {
                val (n, _) = nameOf(it.key)
                val d = DateFormat.format("dd/MM/yyyy HH:mm", Date((last[it.key] ?: 0L) * 1000L))
                trf("%s: %s foto, ultima il %s", n, it.value, d)
            }
    }

    private fun bigFiles(): List<String>? {
        if (!hasMedia()) return null
        val out = ArrayList<String>()
        try {
            val proj = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            val cursor = contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                proj,
                null,
                null,
                MediaStore.MediaColumns.SIZE + " DESC"
            )
            if (cursor != null) {
                cursor.use { c ->
                    var n = 0
                    while (c.moveToNext() && n < 12) {
                        val name = c.getString(0) ?: tr("(senza nome)")
                        val size = c.getLong(1)
                        out.add(name + " — " + Formatter.formatFileSize(this, size))
                        n++
                    }
                }
            }
        } catch (e: Exception) {
            // ignora
        }
        return out
    }

    private fun storageLine(): String {
        return try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val total = sf.totalBytes
            val free = sf.availableBytes
            tr("Spazio libero: ") + Formatter.formatFileSize(this, free) +
                " su " + Formatter.formatFileSize(this, total)
        } catch (e: Exception) {
            tr("Spazio non disponibile.")
        }
    }
}
