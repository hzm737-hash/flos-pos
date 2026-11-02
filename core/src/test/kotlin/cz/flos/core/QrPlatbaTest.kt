package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPlatbaTest {
    @Test fun domesticAccountToIban() {
        val acc = CzechBankAccount.parse("19-2000145399/0800")!!
        assertEquals("CZ6508000000192000145399", acc.iban)
        assertEquals("19-2000145399/0800", acc.toString())
        assertEquals(acc, CzechBankAccount.fromIban("CZ65 0800 0000 1920 0014 5399"))
    }

    @Test fun rejectsBadChecksum() {
        assertNull(CzechBankAccount.parse("19-2000145398/0800"))
        assertFalse(CzechBankAccount.isValidIban("CZ6508000000192000145398"))
        assertTrue(CzechBankAccount.isValidIban("CZ6508000000192000145399"))
    }

    @Test fun buildsSpayd() {
        val qr = QrPlatba(
            iban = "CZ6508000000192000145399",
            amount = 48050,
            variableSymbol = "20250001",
            message = "Účtenka č. 2025*0001 – Kavárna Žluťoučká",
        )
        assertEquals(
            "SPD*1.0*ACC:CZ6508000000192000145399*AM:480.50*CC:CZK*X-VS:20250001*MSG:UCTENKA C. 2025 0001  KAVARNA ZLUTOUCKA",
            qr.toSpayd(),
        )
        assertTrue(qr.toMatrix().width > 20)
    }
}
