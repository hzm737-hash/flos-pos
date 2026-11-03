package cz.flos.core.bank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StatementTest {

    /** Builds a GPC "075" line field by field, so the column positions are tested, not copied. */
    private fun gpc075(
        own: String, counter: String, doc: String, haler: Long, code: Char, vs: String, bank: String, ks: String,
        ss: String, date: String, name: String, currency: String = "0203",
    ): String {
        val sb = StringBuilder("075")
        sb.append(own.padStart(16, '0'))                 // 4-19
        sb.append(counter.padStart(16, '0'))             // 20-35
        sb.append(doc.padStart(13, '0'))                 // 36-48
        sb.append(haler.toString().padStart(12, '0'))    // 49-60
        sb.append(code)                                  // 61
        sb.append(vs.padStart(10, '0'))                  // 62-71
        sb.append("00").append(bank.padStart(4, '0')).append(ks.padStart(4, '0')) // 72-81
        sb.append(ss.padStart(10, '0'))                  // 82-91
        sb.append(date)                                  // 92-97
        sb.append(name.padEnd(20).take(20))              // 98-117
        sb.append('0')                                   // 118
        sb.append(currency)                              // 119-122
        sb.append(date)                                  // 123-128
        check(sb.length == 128)
        return sb.toString()
    }

    private val gpcFile: ByteArray = listOf(
        "0740000002900123456FLOS S.R.O.         01112500000001234500+00000001500000+00000000500000000000000000000002111125              ",
        gpc075("2900123456", "192000145399", "1", 45_000, '2', "20260042", "0800", "0308", "0", "021125", "Jan Novák"),
        gpc075("2900123456", "123456789", "2", 120_000, '1', "999", "0100", "0", "0", "021125", "Dodavatel"),
        gpc075("2900123456", "0", "3", 1_590, '2', "0", "2010", "0", "0", "021125", "John Smith", "0978"),
        "07600000000000000000000000000000021125/VS/20260043 Uctenka 2026-00043",
        gpc075("2900123456", "555", "4", 30_000, '5', "20260044", "0300", "0", "0", "021125", "Storno"),
    ).joinToString("\r\n").toByteArray(charset("windows-1250"))

    @Test fun readsGpcCreditsOnly() {
        assertEquals(StatementParser.Format.GPC, StatementParser.detect(gpcFile))
        val list = StatementParser.parse(gpcFile)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals(45_000L, first.amount)
        assertEquals("20260042", first.variableSymbol)
        assertEquals("Jan Novák", first.counterName)
        assertEquals("192000145399/0800", first.counterAccount)
        assertEquals("CZK", first.currency)
        assertTrue(first.sourceId.startsWith("gpc:2900123456:"))
        // A foreign payment with VS only in the message line that follows.
        val foreign = list[1]
        assertEquals(1_590L, foreign.amount)
        assertEquals("EUR", foreign.currency)
        assertEquals("20260043", foreign.variableSymbol)
        assertTrue(foreign.message!!.contains("Uctenka"))
        // The same file read twice gives the same ids, so nothing is counted twice.
        assertEquals(list.map { it.sourceId }, StatementParser.parse(gpcFile).map { it.sourceId })
    }

    private val camt = """<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
 <BkToCstmrStmt>
  <Stmt>
   <Id>123</Id>
   <Acct><Id><IBAN>CZ6508000000192000145399</IBAN></Id><Ccy>CZK</Ccy></Acct>
   <Ntry>
    <NtryRef>1</NtryRef>
    <Amt Ccy="CZK">450.00</Amt>
    <CdtDbtInd>CRDT</CdtDbtInd>
    <Sts>BOOK</Sts>
    <BookgDt><Dt>2025-11-02</Dt></BookgDt>
    <AcctSvcrRef>A-1</AcctSvcrRef>
    <NtryDtls><TxDtls>
     <Refs><EndToEndId>VS20260042</EndToEndId></Refs>
     <RltdPties><Dbtr><Nm>Jan Novák</Nm></Dbtr><DbtrAcct><Id><IBAN>CZ5503000000000123456789</IBAN></Id></DbtrAcct></RltdPties>
     <RmtInf><Ustrd>Večeře</Ustrd></RmtInf>
    </TxDtls></NtryDtls>
   </Ntry>
   <Ntry>
    <Amt Ccy="CZK">1200.00</Amt>
    <CdtDbtInd>DBIT</CdtDbtInd>
    <Sts>BOOK</Sts>
    <BookgDt><Dt>2025-11-02</Dt></BookgDt>
    <AcctSvcrRef>A-2</AcctSvcrRef>
   </Ntry>
   <Ntry>
    <Amt Ccy="CZK">395.50</Amt>
    <CdtDbtInd>CRDT</CdtDbtInd>
    <Sts><Cd>BOOK</Cd></Sts>
    <BookgDt><Dt>2025-11-02</Dt></BookgDt>
    <AcctSvcrRef>A-3</AcctSvcrRef>
    <NtryDtls><TxDtls>
     <Refs><EndToEndId>NOTPROVIDED</EndToEndId></Refs>
     <AmtDtls><InstdAmt><Amt Ccy="EUR">15.90</Amt></InstdAmt></AmtDtls>
     <RltdPties><Dbtr><Nm>John Smith</Nm></Dbtr></RltdPties>
     <RmtInf><Ustrd>/VS/20260043 Uctenka 2026-00043</Ustrd></RmtInf>
    </TxDtls></NtryDtls>
   </Ntry>
   <Ntry>
    <Amt Ccy="CZK">99.00</Amt>
    <CdtDbtInd>CRDT</CdtDbtInd>
    <Sts>PDNG</Sts>
    <AcctSvcrRef>A-4</AcctSvcrRef>
   </Ntry>
  </Stmt>
 </BkToCstmrStmt>
</Document>""".toByteArray()

    @Test fun readsCamt053() {
        assertEquals(StatementParser.Format.CAMT, StatementParser.detect(camt))
        val list = StatementParser.parse(camt)
        assertEquals(2, list.size)
        with(list[0]) {
            assertEquals(45_000L, amount)
            assertEquals("20260042", variableSymbol)
            assertEquals("Jan Novák", counterName)
            assertEquals("CZ5503000000000123456789", counterAccount)
            assertEquals("Večeře", message)
            assertEquals("camt:CZ6508000000192000145399:A-1", sourceId)
        }
        with(list[1]) {
            assertEquals(39_550L, amount)
            assertEquals("20260043", variableSymbol)
            assertEquals(1_590L, originalAmount)
            assertEquals("EUR", originalCurrency)
        }
    }

    @Test fun refusesExternalEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt><Ntry><Amt Ccy="CZK">1</Amt><CdtDbtInd>CRDT</CdtDbtInd><AddtlNtryInf>&x;</AddtlNtryInf></Ntry></Stmt></BkToCstmrStmt></Document>""".toByteArray()
        val result = runCatching { StatementParser.parse(evil) }
        assertTrue(result.isFailure || result.getOrThrow().none { it.message.orEmpty().contains("root") })
    }

    @Test fun vsFromReferences() {
        assertEquals("123", StatementParser.vsOf("VS:0000000123"))
        assertEquals("20260042", StatementParser.vsOf("/VS/20260042/SS//KS/0308"))
        assertEquals("777", StatementParser.vsOf("777"))
        assertNull(StatementParser.vsOf("NOTPROVIDED"))
        assertNull(StatementParser.vsOf("INV-2025-ABC"))
    }

    @Test fun readsZippedStatementAndMailAttachment() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("vypis.gpc")); z.write(gpcFile); z.closeEntry() }
        }.toByteArray()
        assertEquals(2, StatementParser.parse(zip).size)

        val mail = """From: vypisy@banka.cz
Subject: =?UTF-8?B?${Base64.getEncoder().encodeToString("Denní výpis".toByteArray())}?=
Content-Type: multipart/mixed; boundary="b1"

--b1
Content-Type: text/plain; charset=UTF-8

V příloze zasíláme výpis z účtu.
--b1
Content-Type: application/octet-stream; name="vypis_20251102.xml"
Content-Disposition: attachment; filename="vypis_20251102.xml"
Content-Transfer-Encoding: base64

${Base64.getMimeEncoder().encodeToString(camt)}
--b1--
""".replace("\n", "\r\n").toByteArray()
        val files = Mime.attachments(mail)
        assertEquals(listOf("vypis_20251102.xml"), files.map { it.name })
        assertEquals(2, StatementParser.parse(files[0].bytes).size)
        assertEquals("V příloze zasíláme výpis z účtu.", Mime.parse(mail).text.trim())
    }
}
