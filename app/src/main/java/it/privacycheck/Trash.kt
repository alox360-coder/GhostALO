package it.privacycheck

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

class TrashBatch(val batch: Long, val count: Int, val bytes: Long)

/**
 * Cestino: i file eliminati vengono spostati in una cartella nascosta dell'archivio
 * (stesso volume, quindi lo spostamento è istantaneo) e cancellati davvero solo dopo
 * il numero di giorni scelto. Un indice di testo ricorda da dove arrivano.
 */
object Trash {
    private const val DAY = 24L * 3600L * 1000L
    const val DIR_NAME = ".GhostALO_trash"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trash", Context.MODE_PRIVATE)
    private fun index(ctx: Context) = File(ctx.filesDir, "trash.idx")
    fun dir(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("enabled", v).apply() }
    fun days(ctx: Context): Int = prefs(ctx).getInt("days", 7)
    fun setDays(ctx: Context, d: Int) { prefs(ctx).edit().putInt("days", d).apply() }

    /** Il cestino si usa solo se attivo e se l'app può scrivere nell'archivio. */
    fun active(ctx: Context): Boolean = enabled(ctx) && Cleaner.hasAllFiles()

    private class Line(val id: String, val batch: Long, val size: Long, val path: String)

    private fun read(ctx: Context): MutableList<Line> {
        val out = ArrayList<Line>()
        try {
            val f = index(ctx)
            if (!f.exists()) return out
            f.forEachLine { l ->
                val p = l.split('\t', limit = 4)
                if (p.size == 4) {
                    val b = p[1].toLongOrNull()
                    val s = p[2].toLongOrNull()
                    if (b != null && s != null) out.add(Line(p[0], b, s, p[3]))
                }
            }
        } catch (e: Exception) {
            // ignora
        }
        return out
    }

    private fun write(ctx: Context, lines: List<Line>) {
        try {
            index(ctx).writeText(lines.joinToString("") { it.id + "\t" + it.batch + "\t" + it.size + "\t" + it.path + "\n" })
        } catch (e: Exception) {
            // ignora
        }
    }

    private fun moveOne(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        if (src.isFile) {
            val free = try {
                android.os.StatFs((dst.parentFile ?: dst).absolutePath).availableBytes
            } catch (e: Exception) {
                0L
            }
            if (free < src.length() + 50L * 1024L * 1024L) return false
            return try {
                src.copyTo(dst, overwrite = true)
                if (src.delete()) true else { dst.delete(); false }
            } catch (e: Exception) {
                dst.delete()
                false
            }
        }
        return false
    }

    /** Sposta nel cestino. Restituisce (byte spostati, elementi non spostati). */
    @Synchronized
    fun moveAll(ctx: Context, items: List<File>): Pair<Long, Int> {
        val d = dir()
        d.mkdirs()
        try {
            File(d, ".nomedia").createNewFile()
        } catch (e: Exception) {
            // ignora
        }
        val batch = System.currentTimeMillis()
        var bytes = 0L
        var failed = 0
        var n = 0
        val sb = StringBuilder()
        val scanned = ArrayList<String>()
        try {
            for (f in items) {
                Work.check()
                if (!f.exists()) continue
                val size = if (f.isDirectory) Cleaner.dirSize(f) else f.length()
                val id = java.lang.Long.toHexString(batch) + "_" + n
                n++
                if (moveOne(f, File(d, id))) {
                    bytes += size
                    sb.append(id).append('\t').append(batch).append('\t').append(size).append('\t').append(f.absolutePath).append('\n')
                    scanned.add(f.absolutePath)
                } else {
                    failed++
                }
            }
        } finally {
            // L'indice si scrive sempre, anche se l'operazione viene annullata a metà:
            // ciò che è già stato spostato resta recuperabile.
            try {
                index(ctx).appendText(sb.toString())
            } catch (e: Exception) {
                // ignora
            }
            scan(ctx, scanned)
        }
        return Pair(bytes, failed)
    }

    private fun scan(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
            // ignora
        }
    }

    @Synchronized
    fun batches(ctx: Context): List<TrashBatch> {
        val d = dir()
        val lines = read(ctx).filter { File(d, it.id).exists() }
        return lines.groupBy { it.batch }
            .map { (b, l) -> TrashBatch(b, l.size, l.sumOf { it.size }) }
            .sortedByDescending { it.batch }
    }

    @Synchronized
    fun totalBytes(ctx: Context): Long = batches(ctx).sumOf { it.bytes }

    /** Rimette i file dove erano. Restituisce quanti sono stati ripristinati. */
    @Synchronized
    fun restore(ctx: Context, batch: Long): Pair<Int, Int> {
        val d = dir()
        val all = read(ctx)
        val keep = ArrayList<Line>()
        var ok = 0
        var failed = 0
        val scanned = ArrayList<String>()
        for (l in all) {
            if (l.batch != batch) { keep.add(l); continue }
            val src = File(d, l.id)
            if (!src.exists()) continue
            var target = File(l.path)
            if (target.exists()) target = File(target.parentFile, target.name + ".ripristinato")
            target.parentFile?.mkdirs()
            if (moveOne(src, target)) {
                ok++
                scanned.add(target.absolutePath)
            } else {
                failed++
                keep.add(l)
            }
        }
        write(ctx, keep)
        scan(ctx, scanned)
        return Pair(ok, failed)
    }

    /** Elimina davvero i file scaduti (o tutti). Restituisce i byte liberati. */
    @Synchronized
    fun purge(ctx: Context, all: Boolean): Long {
        if (!Cleaner.hasAllFiles()) return 0L
        val d = dir()
        val limit = System.currentTimeMillis() - days(ctx) * DAY
        val keep = ArrayList<Line>()
        var freed = 0L
        for (l in read(ctx)) {
            val f = File(d, l.id)
            if (!f.exists()) continue
            if (all || l.batch < limit) {
                if (Cleaner.deleteTree(f)) freed += l.size else keep.add(l)
            } else {
                keep.add(l)
            }
        }
        write(ctx, keep)
        return freed
    }
}
