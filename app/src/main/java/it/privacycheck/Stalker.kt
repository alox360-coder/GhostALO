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
            ctx.assets.open("stalkerware.txt").bufferedReader().useLines { lines ->
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
