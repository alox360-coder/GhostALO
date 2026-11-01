package it.privacycheck

import android.content.Context
import android.content.pm.PackageInfo
import java.security.MessageDigest

/**
 * Elenco di indicatori di stalkerware (nomi di pacchetto e impronte SHA-1 delle firme),
 * letto da assets/stalkerware.txt. Condiviso tra la scansione e i controlli in background.
 */
object Stalker {
    private var pk: Set<String>? = null
    private var ce: Set<String>? = null

    @Synchronized
    fun load(ctx: Context): Pair<Set<String>, Set<String>> {
        val a = pk
        val b = ce
        if (a != null && b != null) return Pair(a, b)
        val p = HashSet<String>()
        val c = HashSet<String>()
        try {
            val custom = customFile(ctx)
            val input = if (custom.exists()) custom.inputStream() else ctx.assets.open("stalkerware.txt")
            input.bufferedReader().useLines { lines ->
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
        pk = p
        ce = c
        return Pair(p, c)
    }

    private fun customFile(ctx: Context) = java.io.File(ctx.filesDir, "stalkerware_custom.txt")

    fun isCustom(ctx: Context): Boolean = customFile(ctx).exists()

    /** Legge un elenco dal testo di un file: formato ioc.yaml di Echap o formato P:/C: . */
    fun parse(text: String): Pair<Set<String>, Set<String>> {
        val p = HashSet<String>()
        val c = HashSet<String>()
        var section = ""
        val pkgRe = Regex("[A-Za-z0-9_.]+")
        val certRe = Regex("[0-9a-fA-F]{40}")
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            if (t.startsWith("P:")) { val v = t.substring(2).trim(); if (pkgRe.matches(v)) p.add(v); continue }
            if (t.startsWith("C:")) { val v = t.substring(2).trim(); if (certRe.matches(v)) c.add(v.uppercase()); continue }
            if (t.startsWith("- name:")) { section = ""; continue }
            if (t == "packages:") { section = "p"; continue }
            if (t == "certificates:") { section = "c"; continue }
            if (t.endsWith(":") && !t.startsWith("-")) { section = "x"; continue }
            if (t.startsWith("- ")) {
                val v = t.substring(2).trim().trim('"', '\'')
                if (section == "p" && pkgRe.matches(v)) p.add(v)
                if (section == "c" && certRe.matches(v)) c.add(v.uppercase())
            }
        }
        return Pair(p, c)
    }

    /** Importa un elenco da file. Restituisce (app, firme) importate, o null se il file non è valido. */
    @Synchronized
    fun import(ctx: Context, text: String): Pair<Int, Int>? {
        val (p, c) = parse(text)
        if (p.size + c.size < 50) return null
        val sb = StringBuilder("# Elenco importato dall'utente\n")
        for (x in p.sorted()) sb.append("P:").append(x).append('\n')
        for (x in c.sorted()) sb.append("C:").append(x).append('\n')
        customFile(ctx).writeText(sb.toString())
        pk = null
        ce = null
        return Pair(p.size, c.size)
    }

    @Synchronized
    fun resetToBundled(ctx: Context) {
        customFile(ctx).delete()
        pk = null
        ce = null
    }

    fun certHashes(p: PackageInfo): List<String> {
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
}
