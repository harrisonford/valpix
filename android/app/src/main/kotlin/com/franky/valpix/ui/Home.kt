package com.franky.valpix.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.franky.valpix.Amount
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.Theme
import com.franky.valpix.core.HistoryEntry
import com.franky.valpix.core.Money
import com.franky.valpix.core.PesoFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(model: AppModel) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopLine(model)
        Spacer(Modifier.weight(1f))
        // Tap the number for past payments; hold it for the wallet itself.
        Box(
            Modifier.combinedClickable(
                interactionSource = null, indication = null,
                onClickLabel = "Movimientos", onLongClickLabel = "Billetera",
                onLongClick = { model.flow = AppModel.Flow.Wallet },
                onClick = { model.flow = AppModel.Flow.History },
            )
        ) { BalanceText(model, 60.sp) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
            GlassButton(Icons.Outlined.QrCodeScanner, "Pagar", enabled = model.hasRoute) { model.flow = AppModel.Flow.Scan }
            GlassButton(Icons.Outlined.QrCode2, "Cobrar", enabled = model.hasRoute) { model.flow = AppModel.Flow.Charge }
        }
    }
}

/** The balance: whole pesos in white, four decimals in gray, never still. */
@Composable
fun BalanceText(model: AppModel, size: TextUnit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = System.currentTimeMillis() }
    }
    val parts = PesoFormat.parts(model.balance(now))
    Amount(parts.whole, size, spoken = "${parts.whole} pesos", modifier = Modifier.padding(horizontal = 24.dp), decimals = parts.decimals)
}

@Composable
fun HistoryScreen(model: AppModel) {
    LaunchedEffect(Unit) { model.loadHistory() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 64.dp, bottom = 28.dp)) { BalanceText(model, 34.sp) }
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            items(model.history, key = { it.signature + it.kind }) { entry ->
                Column {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.hairline))
                    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Name and time share the left; the amount keeps the right edge.
                        Row(Modifier.weight(1f)) {
                            BasicText(model.displayName(entry.counterparty, entry.name), Modifier.weight(1f, fill = false), style = Theme.caption(), maxLines = 1)
                            BasicText(time(entry.ts), Modifier.padding(start = 8.dp), style = Theme.caption(Theme.dim), maxLines = 1)
                        }
                        BasicText(amount(entry), Modifier.padding(start = 12.dp), style = Theme.caption(Theme.foreground).copy(fontFeatureSettings = "tnum"), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun amount(entry: HistoryEntry): String {
    val pesos = Money.wholePesos(entry.amountMicro)
    return when (entry.kind) {
        HistoryEntry.Kind.RECEIVED -> PesoFormat.signed(pesos, incoming = true)
        HistoryEntry.Kind.SENT, HistoryEntry.Kind.INVITED -> PesoFormat.signed(pesos, incoming = false)
        HistoryEntry.Kind.JOINED -> ""
    }
}

private fun time(ts: Long): String {
    val date = Date(ts * 1000)
    val then = Calendar.getInstance().apply { time = date }
    val today = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM", Locale("es", "CL")).format(date)
}
