// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.app.login

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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** A login QR code, dark on white whatever the theme, so any scanner reads it. */
@Composable
fun QrCode(
    data: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(data) { qrBitmap(data) }
    Image(
        bitmap.asImageBitmap(),
        description,
        modifier.background(Color.White).padding(16.dp).size(240.dp),
        filterQuality = FilterQuality.None,
    )
}

/** One pixel per module, with ZXing's quiet zone; drawn scaled up without smoothing. */
fun qrBitmap(data: String): Bitmap {
    val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    return createBitmap(matrix.width, matrix.height).apply {
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) this[x, y] = if (matrix[x, y]) BLACK else WHITE
        }
    }
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
