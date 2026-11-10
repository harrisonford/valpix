package com.franky.valpix.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.franky.valpix.AppConfig
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.Keypad
import com.franky.valpix.Theme
import com.franky.valpix.core.FailureReason
import com.franky.valpix.core.PesoFormat
import com.franky.valpix.core.PublicKey
import kotlinx.coroutines.delay

/** After scanning: the amount, who it is for, and one button. */
@Composable
fun ConfirmScreen(model: AppModel, recipient: PublicKey, fixedAmount: ULong?, name: String) {
    var typed by remember { mutableStateOf(0uL) }
    val amount = fixedAmount ?: typed
    val payable = model.canPay(amount)

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        BasicText(
            PesoFormat.whole(amount),
            Modifier.semantics { contentDescription = "$amount pesos" },
            style = Theme.number(60.sp, if (payable) Theme.foreground else Theme.dim),
        )
        BasicText(model.displayName(recipient, name), Modifier.padding(top = 12.dp), style = Theme.caption(), maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (fixedAmount == null) {
            Keypad(typed) { typed = it }
            Spacer(Modifier.size(26.dp))
        }
        GlassButton(Icons.Outlined.Fingerprint, "Aprobar pago", Modifier.padding(bottom = 36.dp), enabled = payable && !model.busy) {
            model.pay(recipient, amount, name)
        }
    }
    LaunchedEffect(Unit) {
        // Debug builds only: lets a script on the Mac drive a payment.
        if (AppConfig.flag("VALPIX_AUTOCONFIRM") && fixedAmount != null) model.pay(recipient, fixedAmount, name)
    }
}

/** After scanning a newcomer's code: who they are, what it costs, one button. */
@Composable
fun InviteScreen(model: AppModel, newcomer: PublicKey, name: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Image(Icons.Outlined.PersonAdd, null, Modifier.size(58.dp), colorFilter = ColorFilter.tint(Theme.foreground))
        BasicText(model.displayName(newcomer, name), Modifier.padding(top = 18.dp), style = Theme.caption(), maxLines = 1)
        model.inviteCostPesos?.let {
            BasicText(PesoFormat.signed(it, incoming = false), Modifier.padding(top = 14.dp), style = Theme.number(30.sp, Theme.dim))
        }
        Spacer(Modifier.weight(1f))
        GlassButton(Icons.Outlined.Fingerprint, "Aprobar invitación", Modifier.padding(bottom = 36.dp), enabled = !model.busy) {
            model.invite(newcomer, name)
        }
    }
    LaunchedEffect(Unit) {
        if (AppConfig.flag("VALPIX_AUTOCONFIRM")) model.invite(newcomer, name)
    }
}

/** Gray while it travels. */
@Composable
fun SendingScreen(amountPesos: ULong) {
    val pulse by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_500, easing = LinearOutSlowInEasing), RepeatMode.Restart),
    )
    Column(
        Modifier.clearAndSetSemantics { contentDescription = "Enviando" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(84.dp).border(1.dp, Theme.dim, CircleShape))
            Box(Modifier.size(84.dp).scale(0.7f + 0.8f * pulse).alpha(1f - pulse).border(1.dp, Theme.foreground.copy(alpha = 0.7f), CircleShape))
        }
        if (amountPesos > 0uL) BasicText(PesoFormat.whole(amountPesos), style = Theme.number(44.sp, Theme.dim))
    }
}

/** White once the chain has confirmed. */
@Composable
fun DoneScreen(model: AppModel, amountPesos: ULong?, name: String, incoming: Boolean) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = if (incoming) "Pago recibido" else "Listo" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.size(84.dp).background(Theme.foreground, CircleShape), contentAlignment = Alignment.Center) {
            Image(Icons.Outlined.Check, null, Modifier.size(40.dp), colorFilter = ColorFilter.tint(Theme.background))
        }
        if (amountPesos != null) BasicText(PesoFormat.signed(amountPesos, incoming), style = Theme.number(44.sp))
        if (name.isNotEmpty()) BasicText(name, style = Theme.caption(), maxLines = 1)
    }
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(3_000)
        if (model.flow is AppModel.Flow.Done) model.flow = null
    }
}

/** It did not go through. One icon says why; nothing was paid. */
@Composable
fun FailedScreen(amountPesos: ULong?, reason: FailureReason) {
    val icon = when (reason) {
        FailureReason.NoRoute -> Icons.Outlined.WifiOff
        FailureReason.Expired -> Icons.Outlined.TimerOff
        is FailureReason.Rejected -> Icons.Outlined.Close
        FailureReason.Unconfirmed -> Icons.Outlined.QuestionMark
    }
    val spoken = when (reason) {
        FailureReason.NoRoute -> "Sin conexión. No se pagó."
        FailureReason.Expired -> "No se confirmó a tiempo. No se pagó."
        is FailureReason.Rejected -> "Pago rechazado. No se pagó."
        FailureReason.Unconfirmed -> "No se pudo confirmar. Revisa tus movimientos antes de pagar de nuevo."
    }
    // Only a failure that certainly did not pay strikes the amount out.
    val certainlyNotPaid = reason != FailureReason.Unconfirmed
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.size(84.dp).border(1.dp, Theme.dim, CircleShape), contentAlignment = Alignment.Center) {
            Image(icon, null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(Theme.foreground))
        }
        if (amountPesos != null && amountPesos > 0uL) {
            BasicText(
                PesoFormat.whole(amountPesos),
                style = Theme.number(44.sp, Theme.dim).copy(textDecoration = if (certainlyNotPaid) TextDecoration.LineThrough else null),
            )
        }
    }
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
}
