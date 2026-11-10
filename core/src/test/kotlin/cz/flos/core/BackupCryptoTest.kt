package cz.flos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val zip = "PK\u0003\u0004 some zip bytes ěščř".toByteArray()

    @Test fun roundTrip() {
        val enc = BackupCrypto.encrypt(zip, "tajneheslo")
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertFalse(BackupCrypto.isEncrypted(zip))
        val opened = BackupCrypto.decrypt(enc, "tajneheslo")
        assertTrue(opened is BackupCrypto.Opened.Ok)
        assertArrayEquals(zip, (opened as BackupCrypto.Opened.Ok).zip)
    }

    @Test fun wrongPassword() {
        val enc = BackupCrypto.encrypt(zip, "tajneheslo")
        assertEquals(BackupCrypto.Opened.WrongPassword, BackupCrypto.decrypt(enc, "jineheslo1"))
    }

    @Test fun sameInputDifferentFiles() {
        assertFalse(BackupCrypto.encrypt(zip, "tajneheslo").contentEquals(BackupCrypto.encrypt(zip, "tajneheslo")))
    }

    @Test fun damaged() {
        val enc = BackupCrypto.encrypt(zip, "tajneheslo")
        assertEquals(BackupCrypto.Opened.Damaged, BackupCrypto.decrypt(enc.copyOf(20), "tajneheslo"))
        val flipped = enc.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(BackupCrypto.decrypt(flipped, "tajneheslo") !is BackupCrypto.Opened.Ok)
    }
}
