package cz.flos.pos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.flos.core.QrPlatba

/** Shows a QR Platba code large enough for a customer to scan from the till screen. */
@Composable
fun QrImage(qr: QrPlatba, size: Dp = 240.dp, modifier: Modifier = Modifier) {
    val matrix = remember(qr) { qr.toMatrix() }
    val description = tr("QR Platba")
    Canvas(
        modifier
            .background(Color.White)
            .padding(12.dp)
            .size(size)
            .semantics { contentDescription = description },
    ) {
        val cell = minOf(this.size.width / matrix.width, this.size.height / matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            // Slight overlap avoids hairline gaps between modules on fractional pixel sizes.
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
