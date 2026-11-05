package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class Round5AdminTest {
    @Test fun permissions() {
        val p = Permissions().with(StaffRole.CASHIER, Permission.STORNO, false).with(StaffRole.CASHIER, Permission.SETTINGS, false)
            .with(StaffRole.MANAGER, Permission.SETTINGS, false).with(StaffRole.OWNER, Permission.SETTINGS, false)
        assertFalse(p.allowed(StaffRole.CASHIER, Permission.STORNO))
        assertTrue(p.allowed(StaffRole.CASHIER, Permission.REFUND))
        assertTrue(p.allowed(StaffRole.OWNER, Permission.SETTINGS))
        val text = p.format()
        assertEquals("MANAGER=SETTINGS;CASHIER=STORNO,SETTINGS", text)
        assertEquals(p.denied.filterKeys { it != StaffRole.OWNER }, Permissions.parse(text).denied)
        assertTrue(Permissions.parse("").allowed(StaffRole.CASHIER, Permission.STORNO))
        assertTrue(Permissions.parse("x=y;CASHIER=NOPE").allowed(StaffRole.CASHIER, Permission.STORNO))
        assertTrue(p.with(StaffRole.CASHIER, Permission.STORNO, true).allowed(StaffRole.CASHIER, Permission.STORNO))
    }

    @Test fun roles() {
        val bo = listOf("Eva" to "CASHIER", "Petr" to "MANAGER")
        assertEquals(StaffRole.CASHIER, Permissions.roleOf("eva", bo, null))
        assertEquals(StaffRole.MANAGER, Permissions.roleOf("Jana", bo, "Jana: vedoucí\nKarel: pokladní"))
        assertEquals(StaffRole.CASHIER, Permissions.roleOf("Karel", emptyList(), "Jana: vedoucí\nKarel: pokladní"))
        assertEquals(StaffRole.OWNER, Permissions.roleOf("", emptyList(), ""))
    }

    private fun text(b: BusinessInfo, kind: DocumentKind = DocumentKind.RECEIPT): String {
        val cart = Cart().add(1, "Káva", 5900, VatRate.REDUCED)
        return ReceiptLayout.toPlainText(ReceiptLayout(32).layout(b, Receipt("2026-000001", LocalDateTime.of(2026, 11, 5, 12, 0), "Eva", cart, PaymentMethod.CARD, kind = kind)), 32)
    }

    @Test fun defaultsPrintTheSameReceipt() {
        val classic = BusinessInfo("Bistro", listOf("Ulice 1"), vatPayer = true)
        val t = text(classic)
        assertTrue(t.contains("Obsluha: Eva"))
        assertTrue(t.contains("CELKEM"))
        assertEquals(t, text(classic.copy(style = ReceiptStyle())))
    }

    @Test fun layoutOptions() {
        val b = BusinessInfo("Bistro", vatPayer = true, contacts = listOf("tel. 777 123 456", "www.bistro.cz"), registry = "Zapsáno v OR, C 123",
            style = ReceiptStyle(showCashier = false, showItemVat = false, bigTotal = true, extraFooter = "Instagram @bistro", documentTerms = "Splatnost 7 dní."))
        val t = text(b)
        assertTrue(t.contains("www.bistro.cz"))
        assertFalse(t.contains("Obsluha"))
        assertTrue(t.contains("Instagram @bistro"))
        assertTrue(t.contains("Zapsáno v OR, C 123"))
        assertFalse(t.contains("Splatnost 7 dní."))
        assertTrue(text(b, DocumentKind.PROFORMA).contains("Splatnost 7 dní."))
    }
}
