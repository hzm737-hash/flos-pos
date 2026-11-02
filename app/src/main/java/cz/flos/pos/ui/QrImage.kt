package cz.flos.pos.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.flos.core.QrPlatba

/** Shows a QR Platba code large enough for a customer to scan from the till screen. */
@Composable
fun QrImage(qr: QrPlatba, size: Dp = 240.dp, modifier: Modifier = Modifier) {
    val bitmap = remember(qr) {
        val m = qr.toMatrix()
        val pixels = IntArray(m.width * m.height) { i ->
            if (m[i % m.width, i / m.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = "QR Platba",
        filterQuality = FilterQuality.None,
        modifier = modifier.background(Color.White).padding(12.dp).size(size),
    )
}
