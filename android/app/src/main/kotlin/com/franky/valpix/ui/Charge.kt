package com.franky.valpix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.franky.valpix.Amount
import com.franky.valpix.AppConfig
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.Keypad
import com.franky.valpix.Theme
import com.franky.valpix.core.Money
import com.franky.valpix.core.PesoFormat
import com.franky.valpix.core.ValpixLink

/** Getting paid: type an amount, show the QR, wait for the chain. */
@Composable
fun ChargeScreen(model: AppModel) {
    var amount by remember { mutableStateOf(0uL) }
    var showingCode by remember { mutableStateOf(false) }
    var startedAt by remember { mutableDoubleStateOf(0.0) }
    val copy = rememberCopy()

    if (showingCode) {
        val requested = amount.takeIf { it > 0uL }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(30.dp)) {
            // Tap the code to copy it, to be paid by someone who is not here to scan it.
            val code = ValpixLink.Pay(model.publicKey, requested, model.sharedName).string
            QrImage(code, "Código para cobrar", Modifier.size(264.dp), onClick = { copy(code) })
            if (requested != null) Amount(PesoFormat.whole(requested), 44.sp, spoken = "$requested pesos")
        }
        LaunchedEffect(Unit) {
            // Only a payment the chain has confirmed ends this screen.
            val paid = model.nextIncomingPayment(requested, startedAt) ?: return@LaunchedEffect
            model.remember(paid.name, paid.counterparty)
            model.flow = AppModel.Flow.Done(
                Money.wholePesos(paid.amountMicro), model.displayName(paid.counterparty, paid.name), incoming = true,
            )
            model.refresh()
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Amount(PesoFormat.whole(amount), 60.sp, spoken = "$amount pesos", color = if (amount > 0uL) Theme.foreground else Theme.dim)
            Spacer(Modifier.weight(1f))
            Keypad(amount) { amount = it }
            // With no amount typed, this shows an open code a stall can print.
            GlassButton(Icons.Outlined.QrCode2, "Mostrar código", Modifier.padding(top = 26.dp, bottom = 30.dp)) {
                startedAt = model.chainNow
                showingCode = true
            }
        }
        LaunchedEffect(Unit) {
            // Debug builds only: VALPIX_CHARGE=<pesos> goes straight to the code.
            val preset = AppConfig.value("VALPIX_CHARGE")?.toULongOrNull()
            if (AppConfig.flag("VALPIX_AUTOCONFIRM") && preset != null) {
                amount = preset
                startedAt = model.chainNow
                showingCode = true
            }
        }
    }
}
