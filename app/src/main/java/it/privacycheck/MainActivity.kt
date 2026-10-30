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
    private var token = 0

    private var spyPk: Set<String>? = null
    private var spyCert: Set<String>? = null

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
        showIntro()
    }

    override fun onResume() {
        super.onResume()
        if (screen == "cleanHome") showCleanHome()
    }

    override fun onBackPressed() {
        if (screen != "intro") showIntro() else super.onBackPressed()
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
        text("> $s" + "_", size = 22f, color = green, bold = true, pad = 4)
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
        button("[<] Menu principale") { showIntro() }
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
        screen = "intro"
        box.removeAllViews()
        text("> CONTROLLO_PRIVACY_", size = 26f, color = green, bold = true, pad = 4)
        text("Versione 3.1", size = 12f, color = grey, pad = 1)
        text("Nessun accesso a Internet: i dati restano nel telefono.", size = 12f, color = grey, pad = 1)
        sep()
        button("[1] Sicurezza del dispositivo") { showSecurity() }
        button("[2] Scansione privacy + app spia") { startScan() }
        button("[3] Rilevatore di tracker") { startTrackerScan() }
        button("[4] Pulizia e prestazioni") { showCleanHome() }
        button("[?] Informazioni e limiti") { showAbout() }
    }

    private fun showAbout() {
        screen = "about"
        box.removeAllViews()
        title("INFO")
        text(
            "Questa app usa solo le funzioni ufficiali di Android. Non ha il permesso " +
                "Internet, non invia nulla e non elimina niente senza la tua conferma.",
            color = dark
        )
        section("Fonti")
        text(
            "Elenco di app spia (nomi dei pacchetti e firme): progetto " +
                "stalkerware-indicators di Echap, licenza CC-BY 4.0. " +
                "L'elenco è una copia inclusa nell'app e può non essere completo.",
            size = 13f, color = grey
        )
        section("Limiti")
        text(
            "• Non può leggere i dati di altre app né vedere in tempo reale chi usa " +
                "fotocamera e microfono.\n" +
                "• Non può svuotare la cache o la cronologia di altre app: Android lo vieta.\n" +
                "• Non è un antivirus: confronta app e firme con un elenco noto e cerca indizi.\n" +
                "• I risultati sono indizi, non prove.\n" +
                "• Se sospetti un controllo nascosto, il ripristino di fabbrica è la soluzione " +
                "più sicura (salva prima i dati e cambia le password da un altro dispositivo).",
            size = 13f, color = grey
        )
        backButton()
    }

    // ---------- Sicurezza del dispositivo ----------

    private fun showSecurity() {
        screen = "security"
        box.removeAllViews()
        title("SICUREZZA_DISPOSITIVO")
        val res = SecurityCheck.run(this)
        val col = if (res.score >= 80) green else if (res.score >= 60) orange else red
        text("PUNTEGGIO: " + res.score + "/100", size = 24f, color = col, bold = true, pad = 8)
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
        text("Tocca una riga con [!!] o [XX] per aprire l'impostazione relativa.", size = 12f, color = grey, pad = 10)
        button("[>] Ripeti controllo") { showSecurity() }
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
        runScan()
    }

    private fun runScan() {
        token++
        val my = token
        screen = "privacy"
        box.removeAllViews()
        title("SCANSIONE_PRIVACY")
        text("Analisi in corso…", size = 18f, bold = true)
        text("Può richiedere qualche secondo.", color = grey)
        Thread {
            val report = try {
                scan()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) {
                    if (report == null) {
                        box.removeAllViews()
                        title("ERRORE")
                        text("Errore durante l'analisi.", color = red)
                        button("[>] Riprova") { startScan() }
                        backButton()
                    } else {
                        showReport(report)
                    }
                }
            }
        }.start()
    }

    private fun showReport(r: Report) {
        box.removeAllViews()
        title("RISULTATO_PRIVACY")

        if (r.spyHits.isNotEmpty()) {
            text(
                "[XX] APP SPIA NOTE RILEVATE: " + r.spyHits.size,
                size = 17f, color = red, bold = true, pad = 8
            )
            for (h in r.spyHits) text(">> $h", color = red)
            text(
                "Tocca l'app nell'elenco qui sotto per aprirne la scheda e disinstallarla. " +
                    "ATTENZIONE: se sospetti che l'abbia installata un partner o un familiare " +
                    "violento, rimuoverla potrebbe avvisarlo. Valuta prima di contattare un " +
                    "centro antiviolenza (in Italia: 1522, gratuito e attivo 24 ore su 24).",
                size = 12f, color = orange
            )
        } else {
            text(
                "[OK] Nessuna corrispondenza con " + r.stalkerDbSize + " indicatori noti di app spia.",
                color = green, bold = true, pad = 8
            )
        }

        val high = r.flagged.count { it.score >= 4 }
        val mid = r.flagged.size - high
        if (high > 0) {
            text("[!!] $high app con rischio ALTO, $mid con rischio medio.", size = 15f, color = red, bold = true)
        } else if (mid > 0) {
            text("[!!] $mid app da controllare (rischio medio).", size = 15f, color = orange, bold = true)
        } else {
            text("[OK] Nessun segnale evidente nelle altre app.", size = 15f, color = green, bold = true)
        }
        text(
            "Sono indizi, non prove: molte app legittime usano questi permessi. " +
                "Controlla che ti siano familiari e che ti servano.",
            size = 12f, color = grey
        )

        section("APP DA CONTROLLARE")
        if (r.flagged.isEmpty()) {
            text("Nessuna app con segnali significativi.", color = green)
        }
        for (f in r.flagged) {
            val level = if (f.score >= 10) "APP SPIA NOTA" else if (f.score >= 4) "RISCHIO ALTO" else "RISCHIO MEDIO"
            val t = text(
                f.label + "  [" + level + "]\n" +
                    f.reasons.joinToString("\n") { "  - $it" } +
                    "\n  (tocca per aprire la scheda dell'app)",
                size = 13f,
                color = if (f.score >= 4) red else orange,
                pad = 8
            )
            t.setOnClickListener { openApp(f.pkg) }
        }
        if (r.quietCount > 0) {
            text("Altre app installate senza segnali rilevanti: ${r.quietCount}", size = 12f, color = grey)
        }

        section("ACCESSIBILITA ATTIVA")
        text(
            "Possono leggere lo schermo e ciò che scrivi. Normali per app come TalkBack; " +
                "sospetti se non li hai attivati tu.",
            size = 12f, color = grey
        )
        if (r.accLines.isEmpty()) text("[OK] Nessuno attivo.", color = green)
        for (l in r.accLines) text("- $l", size = 13f)

        section("TASTIERE ABILITATE")
        for (l in r.keyboardLines) text("- $l", size = 13f)

        section("ACCESSO FOTOCAMERA")
        text(if (r.camApps.isEmpty()) "Nessuna app non di sistema." else r.camApps.joinToString(", "), size = 13f)

        section("ACCESSO MICROFONO")
        text(if (r.micApps.isEmpty()) "Nessuna app non di sistema." else r.micApps.joinToString(", "), size = 13f)

        section("FOTO DA ALTRE APP")
        if (r.photoLines == null) {
            text(
                "Per questo controllo serve il permesso di leggere le foto. " +
                    "Rilancia il controllo e concedilo.",
                size = 13f, color = grey
            )
        } else if (r.photoLines.isEmpty()) {
            text("[OK] Nessuna foto creata da app diverse dalla fotocamera.", color = green)
        } else {
            text(
                "Foto create da app che non sono la fotocamera. Non è per forza un problema " +
                    "(app di blocco che scattano il selfie a chi sbaglia il PIN, filtri, scanner).",
                size = 12f, color = grey
            )
            for (l in r.photoLines) text("- $l", size = 13f)
        }

        section("SPAZIO")
        text(r.storageLine, size = 13f)
        if (r.bigFiles != null && r.bigFiles.isNotEmpty()) {
            text("File multimediali più grandi:", bold = true, size = 13f)
            for (l in r.bigFiles) text("- $l", size = 12f)
        }

        section("SCORCIATOIE")
        button("[>] Impostazioni Privacy (accessi recenti)") {
            openSettings("android.settings.PRIVACY_SETTINGS")
        }
        button("[>] Impostazioni Accessibilità") {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        button("[>] Sicurezza (amministratori dispositivo)") {
            openSettings(Settings.ACTION_SECURITY_SETTINGS)
        }
        button("[>] Elenco di tutte le app") {
            openSettings(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)
        }
        button("[>] Ripeti il controllo") { startScan() }
        backButton()

        text(
            "Limiti: l'app non può leggere i dati di altre app né dimostrare che nessuno " +
                "registri ciò che digiti. Se hai il fondato sospetto di un controllo " +
                "nascosto, il ripristino di fabbrica è la soluzione più sicura.",
            size = 11f, color = grey, pad = 14
        )
    }

    // ---------- Tracker ----------

    private fun startTrackerScan() {
        token++
        val my = token
        screen = "trackers"
        box.removeAllViews()
        title("RILEVATORE_TRACKER")
        text(
            "Cerco nel codice delle app installate le librerie pubblicitarie e di " +
                "tracciamento più note. Può richiedere uno o due minuti.",
            size = 13f, color = grey
        )
        val pv = text("Avvio…", size = 16f, bold = true, pad = 10)
        Thread {
            val res = try {
                Trackers.scanAll(this) { done, total ->
                    runOnUiThread {
                        if (my == token) pv.text = "Analisi: $done / $total app"
                    }
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (my == token) {
                    if (res == null) {
                        box.removeAllViews()
                        title("ERRORE")
                        text("Errore durante l'analisi.", color = red)
                        backButton()
                    } else {
                        showTrackerReport(res)
                    }
                }
            }
        }.start()
    }

    private fun showTrackerReport(res: TrackerResult) {
        screen = "trackerReport"
        box.removeAllViews()
        title("RISULTATO_TRACKER")
        val withT = res.apps.filter { it.trackers.isNotEmpty() }.sortedByDescending { it.trackers.size }
        if (withT.isEmpty()) {
            text("[OK] Nessun tracker noto trovato in " + res.analyzed + " app.", color = green, bold = true)
        } else {
            text(
                "[!!] " + withT.size + " app su " + res.analyzed + " contengono tracker noti.",
                size = 16f, color = orange, bold = true, pad = 8
            )
            val counts = HashMap<String, Int>()
            for (a in withT) for (t in a.trackers) counts[t] = (counts[t] ?: 0) + 1

            section("TRACKER PIU DIFFUSI")
            for (e in counts.entries.sortedByDescending { it.value }.take(10)) {
                text("- " + e.key + ": " + e.value + " app", size = 13f)
            }

            section("DETTAGLIO PER APP")
            text("Tocca un'app per aprirne la scheda.", size = 12f, color = grey)
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
            if (withT.size > 40) text("… e altre " + (withT.size - 40) + " app.", size = 12f, color = grey)
        }
        text(
            "Un tracker non significa spionaggio: molte app gratuite li usano per pubblicità " +
                "e statistiche. Se un'app ne ha molti e non ti serve, valuta di disinstallarla. " +
                "Il rilevamento si basa sui nomi delle librerie: le app \"offuscate\" possono sfuggire.",
            size = 12f, color = grey, pad = 12
        )
        button("[>] Ripeti analisi") { startTrackerScan() }
        backButton()
    }

    // ---------- Pulizia e prestazioni ----------

    private fun showCleanHome() {
        screen = "cleanHome"
        box.removeAllViews()
        title("PULIZIA_E_PRESTAZIONI")
        text(
            "Cerca file inutili e mostra cosa occupa spazio. Prima di eliminare qualsiasi " +
                "cosa vedi l'elenco e ti viene chiesta conferma. Servono due permessi " +
                "speciali, che concedi tu dalle impostazioni di Android.",
            size = 13f, color = grey
        )

        val allFiles = Cleaner.hasAllFiles()
        text(
            if (allFiles) "[OK] Accesso ai file: concesso" else "[NO] Accesso ai file: non concesso",
            color = if (allFiles) green else red, bold = true, pad = 10
        )
        if (!allFiles) {
            text(
                "Serve per trovare ed eliminare APK, file temporanei, miniature, " +
                    "cartelle vuote e duplicati.",
                size = 12f, color = grey
            )
            button("[>] Concedi accesso a tutti i file") {
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
            if (usage) "[OK] Statistiche di utilizzo: concesse" else "[NO] Statistiche di utilizzo: non concesse",
            color = if (usage) green else red, bold = true, pad = 10
        )
        if (!usage) {
            text(
                "Serve per vedere la cache di ogni app e le app non usate da tempo.",
                size = 12f, color = grey
            )
            button("[>] Concedi accesso alle statistiche") {
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
        }

        if (!allFiles && !usage) {
            text("Concedi almeno uno dei due permessi per iniziare.", color = grey, pad = 10)
        } else {
            button("[>] Avvia scansione") { startCleanScan() }
        }
        backButton()
    }

    private fun startCleanScan() {
        token++
        val my = token
        screen = "cleanScan"
        box.removeAllViews()
        title("SCANSIONE_IN_CORSO")
        text("Può richiedere anche un minuto se hai molti file.", color = grey)
        Thread {
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
        }.start()
    }

    private fun showCleanReport(
        cats: List<JunkCategory>?,
        sizes: List<AppSize>?,
        unused: List<UnusedApp>?
    ) {
        screen = "cleanReport"
        box.removeAllViews()
        title("RISULTATO_PULIZIA")

        section("FILE INUTILI")
        if (cats == null) {
            text("Accesso a tutti i file non concesso: controllo dei file saltato.", color = grey)
        } else if (cats.isEmpty()) {
            text("[OK] Nessun file inutile trovato.", color = green)
        } else {
            val root = Environment.getExternalStorageDirectory().absolutePath
            text(
                "Spunta ciò che vuoi eliminare. Sotto ogni voce vedi alcuni esempi.",
                size = 12f, color = grey
            )
            for (c in cats) {
                val cb = CheckBox(this)
                cb.text = c.title + " - " + c.count + " elementi, " + fmt(c.bytes)
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
                val more = if (c.count > 5) "\n  ... e altri " + (c.count - 5) else ""
                text(preview + more, size = 11f, color = grey, pad = 2)
            }
            button("[>] Elimina selezionati") { confirmDelete(cats) }
        }

        section("CACHE DELLE APP")
        if (sizes == null) {
            text(
                "Per vedere la cache delle app concedi l'accesso alle statistiche di utilizzo.",
                size = 13f, color = grey
            )
        } else {
            val totalCache = sizes.sumOf { it.cache }
            text("Cache totale delle app: " + fmt(totalCache), bold = true)
            text(
                "Android non permette ad altre app di svuotarla in automatico. Tocca un'app " +
                    "per aprire la sua scheda, poi Archiviazione → Svuota cache.",
                size = 12f, color = grey
            )
            for (a in sizes.sortedByDescending { it.cache }.take(15)) {
                val t = text(
                    a.label + " - cache " + fmt(a.cache) + ", totale " + fmt(a.total),
                    size = 13f, pad = 7
                )
                t.setOnClickListener { openApp(a.pkg) }
            }
        }

        section("APP NON USATE DA TEMPO")
        if (unused == null) {
            text("Serve l'accesso alle statistiche di utilizzo.", size = 13f, color = grey)
        } else if (unused.isEmpty()) {
            text("[OK] Nessuna app inutilizzata da oltre 60 giorni.", color = green)
        } else {
            text(
                "Non risultano aperte da oltre 60 giorni. Disinstallarle libera spazio. " +
                    "Tocca per aprire la scheda dell'app.",
                size = 12f, color = grey
            )
            for (u in unused) {
                val since = if (u.daysAgo == null) "non usata da oltre 4 mesi" else "ultimo uso " + u.daysAgo + " giorni fa"
                val t = text(u.label + " - " + fmt(u.total) + " (" + since + ")", size = 13f, pad = 7)
                t.setOnClickListener { openApp(u.pkg) }
            }
        }

        section("CRONOLOGIA BROWSER")
        text(
            "Android non permette ad altre app di cancellare la cronologia dei browser. " +
                "In Chrome: menu ⋮ → Elimina dati di navigazione. In Samsung Internet: " +
                "menu → Impostazioni → Dati personali di navigazione → Elimina.",
            size = 12f, color = grey
        )

        section("PER UN TELEFONO PIU VELOCE")
        text(
            "- Tieni libero almeno il 10-15% dello spazio.\n" +
                "- Disinstalla le app che non usi.\n" +
                "- Riavvia ogni tanto e aggiorna sistema e app.\n" +
                "- Diffida delle app \"booster RAM\": chiudere le app in background le fa " +
                "solo ripartire e consuma più batteria.",
            size = 12f, color = grey
        )

        button("[>] Ripeti scansione") { startCleanScan() }
        backButton()
    }

    private fun confirmDelete(cats: List<JunkCategory>) {
        val sel = cats.filter { it.checked }
        if (sel.isEmpty()) {
            Toast.makeText(this, "Nessuna voce selezionata.", Toast.LENGTH_SHORT).show()
            return
        }
        val n = sel.sumOf { it.count }
        val b = sel.sumOf { it.bytes }
        AlertDialog.Builder(this)
            .setTitle("Conferma eliminazione")
            .setMessage(
                "Verranno eliminati definitivamente $n elementi (" + fmt(b) +
                    "). L'operazione non si può annullare."
            )
            .setPositiveButton("Elimina") { _, _ -> doDelete(sel) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun doDelete(sel: List<JunkCategory>) {
        token++
        val my = token
        screen = "cleanDelete"
        box.removeAllViews()
        title("ELIMINAZIONE")
        text("Eliminazione in corso…", size = 18f, bold = true)
        Thread {
            val res = Cleaner.delete(this, sel)
            runOnUiThread {
                if (my == token) {
                    box.removeAllViews()
                    title("PULIZIA_COMPLETATA")
                    text("Spazio liberato: " + fmt(res.first), size = 20f, color = green, bold = true, pad = 8)
                    if (res.second > 0) {
                        text(res.second.toString() + " file non è stato possibile eliminarli.", color = orange)
                    }
                    button("[>] Nuova scansione") { startCleanScan() }
                    backButton()
                }
            }
        }.start()
    }

    // ---------- Scansione privacy: logica ----------

    private fun loadStalker() {
        if (spyPk != null && spyCert != null) return
        val p = HashSet<String>()
        val c = HashSet<String>()
        try {
            assets.open("stalkerware.txt").bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.startsWith("P:")) {
                        p.add(l.substring(2).trim())
                    } else if (l.startsWith("C:")) {
                        c.add(l.substring(2).trim().uppercase())
                    }
                }
            }
        } catch (e: Exception) {
            // elenco non disponibile
        }
        spyPk = p
        spyCert = c
    }

    private fun certHashes(p: PackageInfo): List<String> {
        val out = ArrayList<String>()
        try {
            val si = p.signingInfo ?: return out
            val sigs = si.apkContentsSigners ?: return out
            for (s in sigs) {
                val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
                out.add(d.joinToString("") { "%02X".format(it) })
            }
        } catch (e: Exception) {
            // ignora
        }
        return out
    }

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
        loadStalker()
        val spyP = spyPk ?: emptySet<String>()
        val spyC = spyCert ?: emptySet<String>()

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
                byCert = certHashes(p).any { it in spyC }
            }
            if (sys && !byPkg) continue

            val label = pm.getApplicationLabel(ai).toString()
            val granted = grantedPerms(p)
            val reasons = ArrayList<String>()
            var score = 0

            if (byPkg) {
                score += 10
                reasons.add("APP SPIA NOTA: il pacchetto è nell'elenco pubblico di stalkerware")
                spyHits.add(label + " (" + p.packageName + ")")
            }
            if (byCert) {
                score += 10
                reasons.add("APP SPIA NOTA: firmata con una chiave usata da stalkerware noti")
                spyHits.add(label + " (" + p.packageName + ")")
            }
            if (p.packageName in accPk) {
                score += 4
                reasons.add("Servizio di accessibilità attivo (può leggere lo schermo e ciò che scrivi)")
            }
            if (p.packageName in adminPk) {
                score += 4
                reasons.add("Amministratore del dispositivo (difficile da rimuovere)")
            }
            if (p.packageName in nlPk) {
                score += 2
                reasons.add("Può leggere tutte le notifiche")
            }
            if (p.packageName in imePk) {
                score += 1
                reasons.add("Tastiera di terze parti (vede ciò che scrivi)")
            }
            if (Manifest.permission.CAMERA in granted) {
                score += 1
                reasons.add("Accesso alla fotocamera")
                if (!sys) cam.add(label)
            }
            if (Manifest.permission.RECORD_AUDIO in granted) {
                score += 1
                reasons.add("Accesso al microfono")
                if (!sys) mic.add(label)
            }
            if (Manifest.permission.READ_SMS in granted) {
                score += 2
                reasons.add("Può leggere gli SMS")
            }
            if (Manifest.permission.READ_CALL_LOG in granted) {
                score += 2
                reasons.add("Può leggere il registro chiamate")
            }
            val installer = try {
                pm.getInstallSourceInfo(p.packageName).installingPackageName
            } catch (e: Exception) {
                null
            }
            if (!sys && (installer == null || installer !in storeInstallers)) {
                score += 2
                reasons.add("Installata fuori da un negozio ufficiale (APK manuale)")
            }

            if (score >= 2) {
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
            if (sys) "$n (app di sistema)" else "$n (app installata da te o da altri)"
        }
        val keyboardLines = imePk.map {
            val (n, sys) = nameOf(it)
            val kind = if (sys) "di sistema" else "di terze parti"
            val def = if (it == defIme) ", in uso" else ""
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
                "$n: ${it.value} foto, ultima il $d"
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
                        val name = c.getString(0) ?: "(senza nome)"
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
            "Spazio libero: " + Formatter.formatFileSize(this, free) +
                " su " + Formatter.formatFileSize(this, total)
        } catch (e: Exception) {
            "Spazio non disponibile."
        }
    }
}
