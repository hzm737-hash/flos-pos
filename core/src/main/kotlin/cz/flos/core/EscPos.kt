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

/** Turns receipt blocks into ESC/POS bytes for thermal printers. */
class EscPosEncoder(
    private val paper: PaperWidth = PaperWidth.MM58,
    private val charset: PrinterCharset = PrinterCharset.ASCII,
    /** Width of the printed QR code as a share of the paper width. */
    private val qrWidthRatio: Double = 0.7,
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
        ReceiptLayout.wrap(l.text, max).forEach { text(it); lf() }
        cmd(GS, '!'.code, 0)
        cmd(ESC, 'E'.code, 0)
    }

    private fun qr(content: String) {
        val matrix = QrPlatba.qrMatrix(content)
        align(Align.LEFT)
        raster(qrImage(matrix))
        lf()
    }

    private fun barcode(code: String) {
        val bars = runCatching { Labels.bars(code) }.getOrNull() ?: return
        align(Align.LEFT)
        raster(barcodeImage(bars))
        align(Align.CENTER)
        text(code); lf()
    }

    /** Bars scaled to whole dots (at most 3 per module), centred, 80 dots high. */
    internal fun barcodeImage(m: BitMatrix, height: Int = 80): Array<BooleanArray> {
        val scale = (paper.dots / m.width).coerceIn(1, 3)
        val left = ((paper.dots - m.width * scale) / 2).coerceAtLeast(0)
        val row = BooleanArray(paper.dots) { x -> val mx = (x - left) / scale; x >= left && mx in 0 until m.width && m[mx, 0] }
        return Array(height) { row }
    }

    /** Scales the QR modules to whole dots and centres the code on a paper-wide 1-bit image. */
    internal fun qrImage(m: BitMatrix): Array<BooleanArray> {
        val quiet = 4
        val modules = m.width + 2 * quiet
        val scale = maxOf(1, (paper.dots * qrWidthRatio).toInt() / modules)
        val size = modules * scale
        val left = (paper.dots - size) / 2
        return Array(size) { y ->
            BooleanArray(paper.dots) { x ->
                val mx = (x - left) / scale - quiet
                val my = y / scale - quiet
                x >= left && x < left + size && mx in 0 until m.width && my in 0 until m.height && m[mx, my]
            }
        }
    }

    /** GS v 0 raster image, sent in bands so small printer buffers don't overflow. */
    private fun raster(rows: Array<BooleanArray>) {
        if (rows.isEmpty()) return
        val widthBytes = (rows[0].size + 7) / 8
        rows.toList().chunked(BAND).forEach { band ->
            cmd(GS, 'v'.code, '0'.code, 0, widthBytes and 0xFF, widthBytes shr 8, band.size and 0xFF, band.size shr 8)
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

    private fun text(s: String) {
        val bytes = when (val name = charset.charsetName) {
            null -> Text.stripDiacritics(s).map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }.toByteArray()
            else -> s.toByteArray(Charset.forName(name))
        }
        out.write(bytes)
    }

    private fun cmd(vararg b: Int) = b.forEach { out.write(it) }

    companion object {
        private const val ESC = 0x1B
        private const val GS = 0x1D
        private const val BAND = 128
    }
}
