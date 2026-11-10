package com.franky.valpix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/**
 * A QR code: dark modules on a white card with a quiet margin, so it scans
 * reliably whatever surrounds it.
 */
@Composable
fun QrImage(text: String, label: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
    }
    Canvas(
        modifier
            .background(Color.White, RoundedCornerShape(22.dp))
            .padding(14.dp)
            .semantics { contentDescription = label }
    ) {
        // Whole pixels per module, centred, so there are no hairlines between them.
        val cell = floor(size.minDimension / matrix.width)
        val origin = Offset((size.width - cell * matrix.width) / 2, (size.height - cell * matrix.height) / 2)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}
