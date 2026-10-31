package it.privacycheck

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

class TypeStat(val label: String) {
    var bytes = 0L
    var count = 0
}

class SpaceReport(
    val total: Long,
    val free: Long,
    val types: List<TypeStat>,
    val apps: Long?,
    val big: List<Pair<File, Long>>,
    val truncated: Boolean
)

/** Misura quanto spazio occupano foto, video, audio, documenti e app. Non modifica nulla. */
object StorageAnalyzer {

    private val photo = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "arw", "cr2", "nef"
    )
    private val video = setOf("mp4", "mkv", "3gp", "avi", "mov", "webm", "m4v", "mpg", "mpeg", "wmv", "flv")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "wma", "mid", "oga")
    private val docs = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "csv", "rtf", "epub", "md"
    )
    private val pack = setOf("zip", "rar", "7z", "tar", "gz", "apk", "xapk", "apks", "obb")

    fun analyze(ctx: Context): SpaceReport {
        val root = Environment.getExternalStorageDirectory()
        val st = StatFs(root.path)
        val total = st.totalBytes
        val free = st.availableBytes

        val tPhoto = TypeStat(tr("Foto"))
        val tVideo = TypeStat("Video")
        val tAudio = TypeStat("Audio")
        val tDocs = TypeStat(tr("Documenti"))
        val tPack = TypeStat(tr("Archivi e APK"))
        val tOther = TypeStat(tr("Altri file"))

        val big = ArrayList<Pair<File, Long>>()
        val stack = ArrayList<File>()
        stack.add(root)
        var visited = 0
        var truncated = false

        while (stack.isNotEmpty()) {
            if (visited >= 600000) {
                truncated = true
                break
            }
            Work.check()
            val dir = stack.removeAt(stack.size - 1)
            val entries = dir.listFiles() ?: continue
            for (e in entries) {
                visited++
                if (e.isDirectory) {
                    stack.add(e)
                } else if (e.isFile) {
                    val len = e.length()
                    val ext = e.name.lowercase().substringAfterLast('.', "")
                    val t = when {
                        ext in photo -> tPhoto
                        ext in video -> tVideo
                        ext in audio -> tAudio
                        ext in docs -> tDocs
                        ext in pack -> tPack
                        else -> tOther
                    }
                    t.bytes += len
                    t.count++
                    if (len >= 20L * 1024L * 1024L && (big.size < 8 || len > big[big.size - 1].second)) {
                        big.add(Pair(e, len))
                        big.sortByDescending { it.second }
                        if (big.size > 8) big.removeAt(big.size - 1)
                    }
                }
            }
        }

        val apps = if (Cleaner.hasUsageAccess(ctx)) {
            try {
                Cleaner.appSizes(ctx).sumOf { it.total }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val types = listOf(tPhoto, tVideo, tAudio, tDocs, tPack, tOther).sortedByDescending { it.bytes }
        return SpaceReport(total, free, types, apps, big, truncated)
    }
}
