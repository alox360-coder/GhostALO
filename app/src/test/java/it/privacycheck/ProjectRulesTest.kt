package it.privacycheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class ProjectRulesTest {
    private fun moduleFile(rel: String): File {
        // Gradle esegue i test dalla cartella app/; in locale può essere la radice del progetto.
        val a = File(rel)
        return if (a.exists()) a else File("app/$rel")
    }

    @Test
    fun nessunPermessoInternet() {
        val m = moduleFile("src/main/AndroidManifest.xml").readText()
        assertFalse("L'app non deve avere il permesso INTERNET", m.contains("android.permission.INTERNET"))
    }

    @Test
    fun lavoroInterrottoSiFerma() {
        var fermato = false
        val t = Thread {
            Thread.currentThread().interrupt()
            try {
                Work.check()
            } catch (e: CancellationException) {
                fermato = true
            }
        }
        t.start()
        t.join()
        assertTrue(fermato)
    }

    /** Legge il file delle traduzioni (mappa piatta stringa -> stringa). */
    private fun readMap(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        fun str(): String {
            assertEquals('"', text[i]); i++
            val sb = StringBuilder()
            while (text[i] != '"') {
                if (text[i] == '\\') {
                    i++
                    when (val c = text[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(text.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(c)
                    }
                } else sb.append(text[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun ws() { while (text[i].isWhitespace()) i++ }
        ws(); assertEquals('{', text[i]); i++
        while (true) {
            ws()
            if (text[i] == '}') break
            val k = str(); ws(); assertEquals(':', text[i]); i++; ws()
            out[k] = str(); ws()
            if (text[i] == ',') i++
        }
        return out
    }

    @Test
    fun traduzioniCoerenti() {
        val m = readMap(moduleFile("src/main/assets/en.json").readText())
        assertTrue(m.size > 500)
        val re = Regex("%(?:\\d\\$)?[sd]")
        for ((k, v) in m) {
            assertTrue("Traduzione vuota: $k", v.isNotBlank())
            assertEquals("Segnaposto diversi in: $k", re.findAll(k).count(), re.findAll(v).count())
        }
    }
}
