package cz.flos.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tables for import and export: Excel .xlsx (written and read without extra libraries) and
 * CSV as Czech Excel saves it (semicolons, UTF-8 or Windows-1250).
 */
object Spreadsheet {
    /** Reads the first sheet of an .xlsx file, or a CSV file, as rows of cell texts. */
    fun read(bytes: ByteArray): List<List<String>> =
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) Xlsx.read(bytes) else Csv.parse(Csv.decode(bytes))
}

object Csv {
    /** UTF-8 (with or without BOM) when valid, otherwise Windows-1250 (older Czech Excel). */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
        return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, charset("windows-1250")) }
            .removePrefix("﻿")
    }

    /** Parses CSV; the separator (";", "," or tab) is guessed from the first line. Quotes as Excel writes them. */
    fun parse(text: String): List<List<String>> {
        val first = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(';', '\t', ',').maxByOrNull { c -> first.count { it == c } } ?: ';'
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                !quoted && c == sep -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    /** Semicolon CSV with a BOM, which Czech Excel opens with the right letters. */
    fun write(rows: List<List<Any?>>): ByteArray = ("﻿" + rows.joinToString("\r\n") { r ->
        r.joinToString(";") { v ->
            val s = when (v) { null -> ""; is Double -> v.toString().replace('.', ','); else -> v.toString() }
            if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    } + "\r\n").toByteArray(Charsets.UTF_8)
}

object Xlsx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    /** Column letters: 0 → A, 26 → AA. */
    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    private fun columnIndex(ref: String): Int = ref.takeWhile { it.isLetter() }.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

    /** One sheet; the first row is bold. Numbers stay numbers, everything else is text. */
    fun write(sheetName: String, rows: List<List<Any?>>): ByteArray {
        val sheet = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            rows.forEachIndexed { r, row ->
                append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    val ref = column(c) + (r + 1)
                    val style = if (r == 0) " s=\"1\"" else ""
                    when (v) {
                        null -> {}
                        is Number -> append("<c r=\"$ref\"$style><v>$v</v></c>")
                        is Boolean -> append("<c r=\"$ref\"$style t=\"b\"><v>${if (v) 1 else 0}</v></c>")
                        else -> append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(sheetName.take(31))}" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to sheet,
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    /** The first sheet of a workbook as rows of texts (numbers as Excel stores them, e.g. "59.9"). */
    fun read(bytes: ByteArray): List<List<String>> {
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e -> if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) parts[e.name.removePrefix("/")] = zip.readBytes() }
        }
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            val items = parse(xml).getElementsByTagNameNS("*", "si")
            (0 until items.length).map { i -> texts(items.item(i) as Element) }
        }.orEmpty()
        val sheetPath = firstSheetPath(parts) ?: return emptyList()
        val sheet = parse(parts[sheetPath] ?: return emptyList())
        val rows = sheet.getElementsByTagNameNS("*", "row")
        val result = mutableListOf<List<String>>()
        for (i in 0 until rows.length) {
            val row = rows.item(i) as Element
            val cells = row.getElementsByTagNameNS("*", "c")
            val values = sortedMapOf<Int, String>()
            var next = 0
            for (j in 0 until cells.length) {
                val c = cells.item(j) as Element
                val col = c.getAttribute("r").takeIf { it.isNotEmpty() }?.let(::columnIndex) ?: next
                next = col + 1
                val v = c.getElementsByTagNameNS("*", "v").item(0)?.textContent
                values[col] = when (c.getAttribute("t")) {
                    "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> texts(c)
                    "b" -> if (v == "1") "1" else "0"
                    else -> v.orEmpty()
                }
            }
            val width = (values.keys.maxOrNull() ?: -1) + 1
            val line = List(width) { values[it].orEmpty() }
            if (line.any { it.isNotBlank() }) result.add(line)
        }
        return result
    }

    private fun parse(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    /** All <t> texts inside an element (rich text runs are joined). */
    private fun texts(e: Element): String {
        val ts = e.getElementsByTagNameNS("*", "t")
        return (0 until ts.length).joinToString("") { ts.item(it).textContent }
    }

    private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
        val wb = parts["xl/workbook.xml"]?.let(::parse) ?: return parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
        val first = wb.getElementsByTagNameNS("*", "sheet").item(0) as? Element ?: return null
        val rid = first.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let(::parse)?.getElementsByTagNameNS("*", "Relationship")
        if (rels != null) for (i in 0 until rels.length) {
            val r = rels.item(i) as Element
            if (r.getAttribute("Id") == rid) {
                val target = r.getAttribute("Target")
                return if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
            }
        }
        return parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
    }
}
