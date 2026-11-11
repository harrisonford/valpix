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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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

    /** Gray that still has to be read: the balance's decimals. */
    val faint = Color(0xFF8E8E8E)

    /** Figures that do not jiggle as they change, heavy enough to read at arm's length. */
    fun number(size: TextUnit, color: Color = foreground, weight: FontWeight = FontWeight.Normal) =
        TextStyle(color = color, fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum")

    fun caption(color: Color = soft) = TextStyle(color = color, fontSize = 15.sp)

    /** The person's own name, at the top of the main screens. */
    fun name(color: Color = foreground) = TextStyle(color = color, fontSize = 20.sp, fontWeight = FontWeight.Medium)

    /** The currency under a figure. */
    fun unit(size: TextUnit) = TextStyle(color = soft, fontSize = size, fontWeight = FontWeight.Medium, letterSpacing = 0.14.em)

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

/**
 * A sum of money: the figure and, under it, the currency, so a bare number
 * is never mistaken for anything else (Kris, 2026-11-11).
 */
@Composable
fun Amount(
    whole: String,
    size: TextUnit,
    /** Read aloud in place of the figures. */
    spoken: String,
    modifier: Modifier = Modifier,
    color: Color = Theme.foreground,
    /** The balance's four decimals, smaller and gray. */
    decimals: String? = null,
    struck: Boolean = false,
) {
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            BasicText(
                whole, Modifier.alignByBaseline(), maxLines = 1, softWrap = false,
                style = Theme.number(size, color).copy(textDecoration = if (struck) TextDecoration.LineThrough else null),
            )
            if (decimals != null) {
                BasicText(
                    ",$decimals", Modifier.alignByBaseline(), maxLines = 1, softWrap = false,
                    style = Theme.number(size * 0.46f, Theme.faint),
                )
            }
        }
        BasicText("CLP", Modifier.padding(top = 2.dp), style = Theme.unit((size.value * 0.26f).coerceAtLeast(13f).sp))
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
