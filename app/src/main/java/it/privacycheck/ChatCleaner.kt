package it.privacycheck

import android.os.Environment
import java.io.File

/** Risultato della ricerca nelle cartelle di WhatsApp e Telegram. */
class ChatReport(
    val found: List<String>,
    val totals: Map<String, Long>,
    val cats: List<JunkCategory>,
    val days: Int
)

/**
 * Cerca i media di WhatsApp, WhatsApp Business e Telegram che si possono eliminare.
 * Legge solo le cartelle pubbliche dei media (Android/media/...); la cache privata
 * delle app (Android/data) è protetta da Android e non è raggiungibile.
 */
object ChatCleaner {

    private const val DAY = 24L * 3600L * 1000L

    private class Place(val app: String, val dir: File, val telegram: Boolean)

    private enum class Kind { RECV, VOICE, SENT, STATUS }

    private fun places(): List<Place> {
        val ext = Environment.getExternalStorageDirectory()
        val out = ArrayList<Place>()
        val seen = HashSet<String>()
        fun add(app: String, d: File, tg: Boolean) {
            if (d.isDirectory && seen.add(d.absolutePath)) out.add(Place(app, d, tg))
        }
        add("WhatsApp", File(ext, "Android/media/com.whatsapp/WhatsApp/Media"), false)
        add("WhatsApp", File(ext, "WhatsApp/Media"), false)
        add("WhatsApp Business", File(ext, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"), false)
        add("WhatsApp Business", File(ext, "WhatsApp Business/Media"), false)
        add("Telegram", File(ext, "Android/media/org.telegram.messenger/Telegram"), true)
        add("Telegram", File(ext, "Android/media/org.telegram.messenger.web/Telegram"), true)
        add("Telegram", File(ext, "Telegram"), true)
        return out
    }

    /** Come trattare una cartella di primo livello: null = ignorala. */
    private fun classify(p: Place, name: String): Kind? {
        val n = name.lowercase()
        if (p.telegram) {
            return when (n) {
                "telegram audio" -> Kind.VOICE
                "telegram images", "telegram video", "telegram documents" -> Kind.RECV
                else -> null
            }
        }
        if (n.startsWith(".statuses")) return Kind.STATUS
        if (n.contains("voice notes")) return Kind.VOICE
        if (n.contains("sticker") || n.contains("profile") || n.contains("wallpaper") ||
            n.contains("avatar")
        ) return null
        val media = listOf("images", "video", "audio", "documents", "animated")
        return if (media.any { n.contains(it) }) Kind.RECV else null
    }

    private fun newCat(app: String, kind: Kind, days: Int): JunkCategory = when (kind) {
        Kind.RECV -> JunkCategory(
            trf("%s: media ricevuti più vecchi di %s giorni", app, days),
            if (app == "Telegram") {
                tr("Foto, video e documenti scaricati. Telegram li riscarica dal cloud quando li apri (non le chat segrete).")
            } else {
                tr("Foto, video, GIF e documenti ricevuti. Le chat restano, ma il file non sarà più nel telefono.")
            },
            true
        )
        Kind.VOICE -> JunkCategory(
            if (app == "Telegram") trf("%s: audio e note vocali più vecchi di %s giorni", app, days)
            else trf("%s: note vocali più vecchie di %s giorni", app, days),
            tr("Messaggi vocali e audio. Se contengono ricordi importanti, lascia la spunta tolta."),
            false
        )
        Kind.SENT -> JunkCategory(
            trf("%s: copie dei media inviati più vecchie di %s giorni", app, days),
            tr("Copie locali di ciò che hai inviato. Se non le hai altrove, eliminandole le perdi."),
            false
        )
        Kind.STATUS -> JunkCategory(
            trf("%s: stati già visti (cache)", app),
            tr("Foto e video degli stati che hai guardato: sono copie temporanee."),
            true
        )
    }

    fun scan(days: Int): ChatReport {
        val limit = System.currentTimeMillis() - days * DAY
        val cats = LinkedHashMap<String, JunkCategory>()
        val totals = LinkedHashMap<String, Long>()
        val found = ArrayList<String>()
        var visited = 0

        for (p in places()) {
            if (!found.contains(p.app)) found.add(p.app)
            val tops = p.dir.listFiles() ?: continue
            for (top in tops) {
                if (!top.isDirectory) continue
                val kind = classify(p, top.name) ?: continue
                val stack = ArrayList<Pair<File, Boolean>>()
                stack.add(Pair(top, false))
                while (stack.isNotEmpty() && visited < 400000) {
                    Work.check()
                    val (dir, inSent) = stack.removeAt(stack.size - 1)
                    val entries = dir.listFiles() ?: continue
                    for (e in entries) {
                        visited++
                        if (e.isDirectory) {
                            stack.add(Pair(e, inSent || e.name.equals("Sent", ignoreCase = true)))
                        } else if (e.isFile && e.name != ".nomedia") {
                            val len = e.length()
                            totals[p.app] = (totals[p.app] ?: 0L) + len
                            val k = if (kind == Kind.RECV && inSent) Kind.SENT else kind
                            if (k == Kind.STATUS || e.lastModified() < limit) {
                                val c = cats.getOrPut(p.app + "|" + k.name) { newCat(p.app, k, days) }
                                c.files.add(e)
                                c.bytes += len
                            }
                        }
                    }
                }
            }
        }

        val order = Kind.values().toList()
        val list = cats.entries
            .sortedWith(compareBy({ it.key.substringBefore('|') }, { order.indexOf(Kind.valueOf(it.key.substringAfter('|'))) }))
            .map { it.value }
            .filter { it.count > 0 }
        return ChatReport(found, totals, list, days)
    }
}
