package com.franky.valpix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.Theme

@Composable
fun Root(model: AppModel) {
    Box(Modifier.fillMaxSize().background(Theme.background).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val flow = model.flow
        when {
            model.phase == AppModel.Phase.STORAGE_FAILED ->
                Image(
                    Icons.Outlined.NoEncryption, "Este teléfono no puede guardar la billetera",
                    Modifier.size(48.dp), colorFilter = ColorFilter.tint(Theme.dim),
                )
            // Nothing is shown until the person unlocks. Tap to try again.
            model.locked -> GlassButton(Icons.Outlined.Fingerprint, "Abrir valpix") { model.requestUnlock() }
            flow != null -> FlowScreen(model, flow)
            model.phase == AppModel.Phase.NEEDS_JOIN -> JoinScreen(model)
            model.phase == AppModel.Phase.READY -> HomeScreen(model)
            else -> Unit
        }
    }
    // A payment in flight cannot be walked away from.
    BackHandler(enabled = model.flow != null && !model.locked) {
        if (model.flow !is AppModel.Flow.Sending) model.flow = null
    }
}

/** Whatever the person is in the middle of, on black with one way out. */
@Composable
private fun FlowScreen(model: AppModel, flow: AppModel.Flow) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (flow) {
                AppModel.Flow.Charge -> ChargeScreen(model)
                AppModel.Flow.Scan -> ScanScreen(model)
                is AppModel.Flow.Confirm -> ConfirmScreen(model, flow.to, flow.amountPesos, flow.name)
                is AppModel.Flow.Invite -> InviteScreen(model, flow.key, flow.name)
                is AppModel.Flow.Adopt -> AdoptScreen(model, flow.seed)
                is AppModel.Flow.Unlock -> UnlockScreen(model, flow.blob)
                is AppModel.Flow.Sending -> SendingScreen(flow.amountPesos)
                is AppModel.Flow.Done -> DoneScreen(model, flow.amountPesos, flow.name, flow.incoming)
                is AppModel.Flow.Failed -> FailedScreen(flow.amountPesos, flow.reason)
                AppModel.Flow.History -> HistoryScreen(model)
                AppModel.Flow.Wallet -> WalletScreen(model)
            }
        }
        if (flow !is AppModel.Flow.Sending) {
            GlassButton(Icons.Outlined.Close, "Cerrar", Modifier.padding(start = 20.dp, top = 8.dp), size = 44.dp) { model.flow = null }
        }
    }
}

/** The person's name and the signal dot, across the top of the main screens. */
@Composable
fun TopLine(model: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        // A long name gives way to the dot, and scrolls inside its own width.
        NameField(model, Modifier.weight(1f, fill = false))
        SignalDot(model.hasRoute, Modifier.padding(start = 10.dp))
    }
}

/** The signal dot: solid with a route to the chain, hollow without. */
@Composable
fun SignalDot(hasRoute: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(7.dp)
            .background(if (hasRoute) Theme.foreground else Color.Transparent, CircleShape)
            .border(1.dp, if (hasRoute) Theme.foreground else Theme.dim, CircleShape)
            .semantics { contentDescription = if (hasRoute) "Con conexión" else "Sin conexión" }
    )
}

private const val NAME_HINT = "tu nombre aquí"

/** The person's own name: tap to edit, kept only on this phone. */
@Composable
fun NameField(model: AppModel, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val style = Theme.name().copy(textAlign = TextAlign.Center)
    // As wide as what it shows, measured here: a text field asked for its
    // own width answers too small and cuts the name (seen on the Pixel).
    val measurer = rememberTextMeasurer()
    val shown = model.name.ifEmpty { NAME_HINT }
    val width = with(LocalDensity.current) { measurer.measure(shown, style, softWrap = false, maxLines = 1).size.width.toDp() } + 8.dp
    BasicTextField(
        value = model.name,
        onValueChange = { model.name = it.replace("\n", "").take(40) },
        modifier = modifier.width(width).defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "Tu nombre" },
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(Theme.foreground),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.Center) {
                // An empty name says where to tap (Kris, 2026-11-10).
                if (model.name.isEmpty()) BasicText(NAME_HINT, style = Theme.name(Theme.dim), maxLines = 1, softWrap = false)
                field()
            }
        },
    )
}
