package cz.flos.core

import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Paper sizes of common Bluetooth thermal printers. */
enum class PaperWidth(val mm: Int, val dots: Int, val chars: Int) {
    MM58(58, 384, 32),
    MM80(80, 576, 48),
}

/**
 * How Czech letters are sent to the printer. Cheap printers differ, so the safe default
 * removes diacritics; the code pages print háčky and čárky on printers that support them.
 */
enum class PrinterCharset(val label: String, val charsetName: String?, val escPosTable: Int?) {
    ASCII("Bez diakritiky (nejbezpečnější)", null, null),
    CP852("CP852 (Latin 2)", "IBM852", 18),
    CP1250("Windows-1250", "windows-1250", 45),
}

/**
 * How fast Flos sends data to a Bluetooth / COM printer. Faster sending is fine for printers with a
 * bigger buffer; if the QR code comes out as random characters, go back to [NORMAL].
 */
enum class PrintSpeed(val label: String, val factor: Int) {
    NORMAL("Normální", 1),
    FAST("Rychlá ×2", 2),
    FASTEST("Nejrychlejší ×3", 3),
}

/**
 * Makes text safe for thermal printers. Symbols that most printer code pages don't have
 * (½ from half & half pizzas, the " · " before a line's note, typographic quotes, emoji …)
 * are written out in plain characters, so they never print as "?".
 */
object PrintText {
    private val map = mapOf(
        '½' to "1/2", '¼' to "1/4", '¾' to "3/4", '⅓' to "1/3", '⅔' to "2/3", '⅛' to "1/8",
        '·' to "-", '•' to "-", '∙' to "-", '‧' to "-",
        '–' to "-", '—' to "-", '‒' to "-", '−' to "-", '‐' to "-", '‑' to "-",
        '‘' to "'", '’' to "'", '‚' to "'", '‛' to "'", '´' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '«' to "\"", '»' to "\"", '‹' to "'", '›' to "'",
        '…' to "...", '×' to "x", '÷' to "/", '€' to "EUR", '№' to "c.",
        '\u00A0' to " ", '\u2007' to " ", '\u202F' to " ", '\u2009' to " ", '\t' to " ",
        '™' to "(TM)", '®' to "(R)", '©' to "(C)", '✓' to "v", '✔' to "v",
    )

    /** Letters without an accent-free form in Unicode, used only when the printer has no code page. */
    private val letters = mapOf(
        'ß' to "ss", 'Ł' to "L", 'ł' to "l", 'Đ' to "D", 'đ' to "d", 'Ø' to "O", 'ø' to "o",
        'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe", 'ı' to "i", '°' to "st",
    )

    private fun invisible(c: Char) =
        c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u2060' || c == '\uFEFF' ||
            c in '\uFE00'..'\uFE0F' || Character.isISOControl(c)

    /** Replaces symbols with plain text and drops invisible characters and emoji. Spacing is kept. */
    fun printable(s: String): String {
        if (s.all { it.code in 32..126 }) return s
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp > 0xFFFF) continue // emoji and other symbols outside the basic plane
            val c = cp.toChar()
            val r = map[c]
            when {
                r != null -> sb.append(r)
                Character.isSurrogate(c) || invisible(c) -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Plain ASCII bytes: accents removed, anything still unknown becomes "?". */
    fun ascii(s: String): ByteArray {
        val plain = buildString { printable(s).forEach { c -> append(letters[c] ?: c.toString()) } }
        return Text.stripDiacritics(plain).map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }.toByteArray()
    }
}

/** Turns receipt blocks into ESC/POS bytes for thermal printers. */
class EscPosEncoder(
    private val paper: PaperWidth = PaperWidth.MM58,
    private val charset: PrinterCharset = PrinterCharset.ASCII,
    /** Width of the printed QR code as a share of the paper width. */
    private val qrWidthRatio: Double = 0.7,
    /**
     * Let the printer draw the QR code itself (GS ( k): about 150 bytes instead of a picture of several KB,
     * so it prints much faster. Most current ESC/POS printers support it; very old or cheap ones print nothing.
     */
    private val nativeQr: Boolean = false,
) {
    private val out = ByteArrayOutputStream()

    /**
     * Encodes [blocks] for the printer. [openDrawer] sends a pulse to the cash drawer connected
     * to the printer's RJ11 port (ESC p 0), used for cash payments.
     */
    fun encode(blocks: List<ReceiptBlock>, cut: Boolean = true, openDrawer: Boolean = false): ByteArray {
        out.reset()
        cmd(ESC, '@'.code) // initialise
        if (openDrawer) cmd(ESC, 'p'.code, 0, 25, 250)
        charset.escPosTable?.let { cmd(ESC, 't'.code, it) }
        for (b in blocks) when (b) {
            is ReceiptBlock.Line -> line(b)
            ReceiptBlock.Separator -> { align(Align.LEFT); text("-".repeat(paper.chars)); lf() }
            is ReceiptBlock.Qr -> qr(b.content)
            is ReceiptBlock.Feed -> cmd(ESC, 'd'.code, b.lines.coerceIn(0, 255))
            is ReceiptBlock.Barcode -> barcode(b.code)
        }
        if (cut) cmd(GS, 'V'.code, 66, 0) // feed and partial cut; ignored by printers without a cutter
        return out.toByteArray()
    }

    private fun line(l: ReceiptBlock.Line) {
        align(l.align)
        cmd(ESC, 'E'.code, if (l.bold) 1 else 0)
        cmd(GS, '!'.code, if (l.large) 0x11 else 0x00)
        val max = if (l.large) paper.chars / 2 else paper.chars
        ReceiptLayout.wrap(PrintText.printable(l.text), max).forEach { text(it); lf() }
        cmd(GS, '!'.code, 0)
        cmd(ESC, 'E'.code, 0)
    }

    private fun qr(content: String) {
        val matrix = QrPlatba.qrMatrix(content)
        if (nativeQr) return nativeQr(content, matrix)
        align(Align.LEFT)
        // Half-size picture that the printer doubles in both directions: a quarter of the bytes, same QR on paper.
        raster(qrImage(matrix, half = true), double = true)
        lf()
    }

    private fun barcode(code: String) {
        val bars = runCatching { Labels.bars(code) }.getOrNull() ?: return
        align(Align.LEFT)
        raster(barcodeImage(bars))
        align(Align.CENTER)
        text(code); lf()
    }

    /** The printer's own QR command, sized like the picture: model 2, error correction M, centred. */
    private fun nativeQr(content: String, m: BitMatrix) {
        val modules = m.width + 8
        val size = ((paper.dots * qrWidthRatio).toInt() / modules).coerceIn(1, 16)
        val data = content.toByteArray(Charsets.US_ASCII)
        val len = data.size + 3
        align(Align.CENTER)
        cmd(GS, '('.code, 'k'.code, 4, 0, 49, 65, 50, 0) // model 2
        cmd(GS, '('.code, 'k'.code, 3, 0, 49, 67, size) // module size in dots
        cmd(GS, '('.code, 'k'.code, 3, 0, 49, 69, 49) // error correction M
        cmd(GS, '('.code, 'k'.code, len and 0xFF, len shr 8, 49, 80, 48)
        out.write(data)
        cmd(GS, '('.code, 'k'.code, 3, 0, 49, 81, 48) // print
        lf()
        align(Align.LEFT)
    }

    /** Bars scaled to whole dots (at most 3 per module), centred, 80 dots high. */
    internal fun barcodeImage(m: BitMatrix, height: Int = 80): Array<BooleanArray> {
        val scale = (paper.dots / m.width).coerceIn(1, 3)
        val left = ((paper.dots - m.width * scale) / 2).coerceAtLeast(0)
        val row = BooleanArray(paper.dots) { x -> val mx = (x - left) / scale; x >= left && mx in 0 until m.width && m[mx, 0] }
        return Array(height) { row }
    }

    /**
     * Scales the QR modules to whole dots and centres the code on a paper-wide 1-bit image.
     * With [half] the image is half the paper width, for printing with the printer's 2× scaling.
     */
    internal fun qrImage(m: BitMatrix, half: Boolean = false): Array<BooleanArray> {
        val quiet = 4
        val modules = m.width + 2 * quiet
        val full = maxOf(1, (paper.dots * qrWidthRatio).toInt() / modules)
        val dots = if (half) paper.dots / 2 else paper.dots
        // Rounded so the doubled code stays close to the size of the full one, but never wider than the paper.
        val scale = if (half) ((full + 1) / 2).coerceAtMost(dots / modules).coerceAtLeast(1) else full
        val size = modules * scale
        val left = ((dots - size) / 2).coerceAtLeast(0)
        return Array(size) { y ->
            BooleanArray(dots) { x ->
                val mx = (x - left) / scale - quiet
                val my = y / scale - quiet
                x >= left && x < left + size && mx in 0 until m.width && my in 0 until m.height && m[mx, my]
            }
        }
    }

    /**
     * GS v 0 raster image, sent in bands so small printer buffers don't overflow.
     * [double] asks the printer to print every dot as 2×2 (mode 3).
     */
    private fun raster(rows: Array<BooleanArray>, double: Boolean = false) {
        if (rows.isEmpty()) return
        // Blank space right of the picture is not sent: fewer bytes, faster over Bluetooth.
        val lastDot = rows.maxOf { r -> r.lastIndexOf(true) }
        if (lastDot < 0) return
        val widthBytes = lastDot / 8 + 1
        rows.toList().chunked(BAND).forEach { band ->
            cmd(GS, 'v'.code, '0'.code, if (double) 3 else 0, widthBytes and 0xFF, widthBytes shr 8, band.size and 0xFF, band.size shr 8)
            for (row in band) {
                for (bx in 0 until widthBytes) {
                    var byte = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < row.size && row[x]) byte = byte or (0x80 shr bit)
                    }
                    out.write(byte)
                }
            }
        }
    }

    private fun align(a: Align) = cmd(ESC, 'a'.code, a.ordinal)
    private fun lf() = out.write(0x0A)

    private fun text(raw: String) {
        val s = PrintText.printable(raw)
        val bytes = when (val name = charset.charsetName) {
            null -> PrintText.ascii(s)
            else -> {
                // Letters the code page doesn't have lose their accent instead of becoming "?".
                val enc = Charset.forName(name).newEncoder()
                s.map { c -> if (enc.canEncode(c)) c.toString() else String(PrintText.ascii(c.toString()), Charsets.US_ASCII) }
                    .joinToString("").toByteArray(Charset.forName(name))
            }
        }
        out.write(bytes)
    }

    private fun cmd(vararg b: Int) = b.forEach { out.write(it) }

    companion object {
        private const val ESC = 0x1B
        private const val GS = 0x1D
        // 24 dot rows (about 1 KB at 80 mm) per command: small enough for the buffer of cheap printers.
        private const val BAND = 24
    }
}
