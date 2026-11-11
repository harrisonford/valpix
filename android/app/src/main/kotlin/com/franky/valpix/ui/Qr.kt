package com.franky.valpix.ui

import androidx.compose.foundation.Canvas
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
fun QrImage(text: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
    }
    Canvas(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Copiar", onClick = onClick) else Modifier)
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

/**
 * Puts a code's text on the clipboard, so it can be sent to someone who is
 * not here to scan it; they paste it into their own scanner. Only for codes
 * that are safe to show anyone: never the one that carries the wallet.
 */
@Composable
fun rememberCopy(): (String) -> Unit {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return remember(context, haptics) {
        { text ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("valpix", text))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Android 13 and later show their own notice that something was copied.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
        }
    }
}
