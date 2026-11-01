package it.privacycheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerParseTest {
    private val cert = "AB".repeat(20)

    @Test
    fun formatoPiuC() {
        val (p, c) = Stalker.parse("P:com.example.spy\nC:${cert.lowercase()}\n# commento\nP:non valido!\nC:123\n")
        assertEquals(setOf("com.example.spy"), p)
        assertEquals(setOf(cert), c)
    }

    @Test
    fun formatoIocYaml() {
        val yaml = """
            - name: Test
              packages:
                - com.a.b
                - "com.c.d"
              certificates:
                - $cert
              websites:
                - example.com
        """.trimIndent()
        val (p, c) = Stalker.parse(yaml)
        assertEquals(setOf("com.a.b", "com.c.d"), p)
        assertEquals(setOf(cert), c)
    }

    @Test
    fun testoQualunqueNonProduceNulla() {
        val (p, c) = Stalker.parse("Ciao\n\nquesto non è un elenco\n- così neanche\n")
        assertTrue(p.isEmpty() && c.isEmpty())
    }
}
