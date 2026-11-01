package it.privacycheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FingerprintTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, size: Int, fill: (Int) -> Byte): File {
        val f = tmp.newFile(name)
        f.writeBytes(ByteArray(size) { fill(it) })
        return f
    }

    @Test
    fun fileUgualiHannnoStessaImpronta() {
        val a = file("a", 300_000) { (it % 251).toByte() }
        val b = file("b", 300_000) { (it % 251).toByte() }
        assertEquals(Cleaner.fingerprint(a), Cleaner.fingerprint(b))
        assertEquals(Cleaner.hash(a), Cleaner.hash(b))
    }

    @Test
    fun differenzaInizialeOFinaleSiVede() {
        val a = file("a", 300_000) { (it % 251).toByte() }
        val b = file("b", 300_000) { if (it == 10 || it == 299_990) 7 else (it % 251).toByte() }
        assertNotEquals(Cleaner.fingerprint(a), Cleaner.fingerprint(b))
    }

    @Test
    fun differenzaNelMezzoLaVedeSoloLHashCompleto() {
        val a = file("a", 300_000) { (it % 251).toByte() }
        val b = file("b", 300_000) { if (it == 150_000) 7 else (it % 251).toByte() }
        // L'impronta veloce non basta da sola: per questo i duplicati usano anche l'hash completo.
        assertEquals(Cleaner.fingerprint(a), Cleaner.fingerprint(b))
        assertNotEquals(Cleaner.hash(a), Cleaner.hash(b))
    }

    @Test
    fun fileInesistente() {
        assertNull(Cleaner.hash(File(tmp.root, "manca")))
    }

    @Test
    fun controlloPrimaDiEliminare() {
        val f = file("grande", 200_000) { 1 }
        val c = JunkCategory("t", "h", true)
        c.files.add(f)
        c.seal()
        c.prints[f] = Cleaner.fingerprint(f)!!
        assertTrue(c.unchanged(f))
        // stessa dimensione e stessa data, ma contenuto diverso all'inizio
        val time = f.lastModified()
        f.writeBytes(ByteArray(200_000) { 2 })
        f.setLastModified(time)
        assertFalse(c.unchanged(f))
    }

    @Test
    fun fileCambiatoOCancellato() {
        val f = file("x", 1000) { 1 }
        val c = JunkCategory("t", "h", true)
        c.files.add(f)
        c.seal()
        assertTrue(c.unchanged(f))
        f.appendBytes(byteArrayOf(1))
        assertFalse(c.unchanged(f))
        f.delete()
        assertFalse(c.unchanged(f))
    }
}
