package com.franky.valpix

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The whole look in one place: pitch black, white, gray, glass. Restyle the
 * app by changing this file.
 */
object Theme {
    val background = Color.Black
    val foreground = Color.White

    /** Decimals, amounts in transit, anything not yet real. */
    val dim = Color(0xFF6B6B6B)

    /** Names and times. */
    val soft = Color(0xFFB3B3B3)
    val hairline = Color(0xFF1C1C1C)
    private val glassFill = Color.White.copy(alpha = 0.10f)
    private val glassEdge = Color.White.copy(alpha = 0.22f)

    /** Thin figures that do not jiggle as they change. */
    fun number(size: TextUnit, color: Color = foreground) =
        TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Thin, fontFeatureSettings = "tnum")

    fun caption(color: Color = soft) = TextStyle(color = color, fontSize = 15.sp)

    /** A translucent circle with a bright rim: glass, on true black. */
    fun Modifier.glassCircle(): Modifier = clip(CircleShape).background(glassFill).border(1.dp, glassEdge, CircleShape)
}

/** A round glass control carrying one icon and no label. */
@Composable
fun GlassButton(
    icon: ImageVector,
    /** Spoken by screen readers; never shown. */
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.3f)
            .size(size)
            .then(with(Theme) { Modifier.glassCircle() })
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, contentDescription = null, Modifier.size(size * 0.38f), colorFilter = ColorFilter.tint(Theme.foreground))
    }
}

/** A number pad for whole pesos. */
@Composable
fun Keypad(value: ULong, max: ULong = 1_000_000_000uL, onChange: (ULong) -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (key in row) {
                    if (key.isEmpty()) {
                        Spacer(Modifier.size(76.dp))
                        continue
                    }
                    Box(
                        Modifier
                            .size(76.dp)
                            .then(with(Theme) { Modifier.glassCircle() })
                            .clickable(role = Role.Button) {
                                if (key == "⌫") {
                                    onChange(value / 10uL)
                                } else {
                                    val next = value * 10uL + key.toULong()
                                    if (next <= max) onChange(next)
                                }
                            }
                            .semantics { contentDescription = if (key == "⌫") "Borrar" else key },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") {
                            Image(Icons.AutoMirrored.Outlined.Backspace, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Theme.foreground))
                        } else {
                            BasicText(key, style = TextStyle(color = Theme.foreground, fontSize = 30.sp, fontWeight = FontWeight.Light))
                        }
                    }
                }
            }
        }
    }
}
