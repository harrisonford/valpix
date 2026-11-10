package com.franky.valpix.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.SendToMobile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.Theme
import com.franky.valpix.core.Keypair
import kotlinx.coroutines.launch

/**
 * The wallet itself: move it to another phone, or make a backup. Both show
 * a QR that IS the wallet, so both ask for approval first.
 */
@Composable
fun WalletScreen(model: AppModel) {
    var shown by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var askingPassword by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var accessPoint by remember { mutableStateOf(model.accessPoint) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun makeBackup() {
        if (password.isEmpty() || working) return
        working = true
        scope.launch {
            try {
                model.backupLink(password)?.let {
                    shown = it
                    password = ""
                    askingPassword = false
                }
            } finally {
                working = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        val code = shown
        when {
            code != null -> QrImage(code, "Código de tu billetera. No lo compartas.", Modifier.size(264.dp))
            askingPassword -> {
                PasswordField(password, "Contraseña del respaldo", onDone = ::makeBackup) { password = it }
                GlassButton(Icons.Outlined.Check, "Crear respaldo", Modifier.padding(top = 28.dp), enabled = password.isNotEmpty() && !working, onClick = ::makeBackup)
            }
            else -> {
                SelectionContainer {
                    BasicText(
                        model.publicKey.base58, Modifier.padding(horizontal = 16.dp),
                        style = TextStyle(color = Theme.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                    )
                }
                // The person's own access point to Solana, tried first.
                BasicTextField(
                    value = accessPoint,
                    onValueChange = {
                        accessPoint = it.replace("\n", "")
                        model.accessPoint = accessPoint
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp).semantics { contentDescription = "Tu propio punto de acceso" },
                    textStyle = TextStyle(color = Theme.soft, fontSize = 13.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                    singleLine = true,
                    cursorBrush = SolidColor(Theme.foreground),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.Center) {
                            if (accessPoint.isEmpty()) BasicText("https://", style = TextStyle(color = Theme.dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace))
                            field()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (shown == null && !askingPassword) {
            Row(Modifier.padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                GlassButton(Icons.Outlined.SendToMobile, "Pasar a otro teléfono") {
                    scope.launch { model.walletLink()?.let { shown = it } }
                }
                GlassButton(Icons.Outlined.EnhancedEncryption, "Respaldo con contraseña") { askingPassword = true }
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, label: String, onDone: () -> Unit, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.replace("\n", "")) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        textStyle = TextStyle(color = Theme.foreground, fontSize = 24.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
        singleLine = true,
        cursorBrush = SolidColor(Theme.foreground),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty()) BasicText("••••••••", style = TextStyle(color = Theme.dim, fontSize = 24.sp))
                field()
            }
        },
    )
}

/** A scanned backup: type its password to bring the wallet onto this phone. */
@Composable
fun UnlockScreen(model: AppModel, blob: ByteArray) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun open() {
        if (password.isEmpty() || working) return
        val attempt = password
        working = true
        scope.launch {
            try {
                wrong = !model.unlock(blob, attempt)
                if (wrong) password = ""
            } finally {
                working = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Image(
            if (wrong) Icons.Outlined.NoEncryption else Icons.Outlined.Lock, null,
            Modifier.padding(bottom = 28.dp).size(48.dp), colorFilter = ColorFilter.tint(if (wrong) Theme.dim else Theme.foreground),
        )
        PasswordField(password, "Contraseña del respaldo", onDone = ::open) { password = it }
        Spacer(Modifier.weight(1f))
        GlassButton(Icons.Outlined.Check, "Abrir respaldo", Modifier.padding(bottom = 36.dp), enabled = password.isNotEmpty() && !working, onClick = ::open)
    }
}

/** A scanned wallet: one button to make it this phone's wallet. */
@Composable
fun AdoptScreen(model: AppModel, seed: ByteArray) {
    val scope = rememberCoroutineScope()
    val key = remember(seed) { Keypair.fromSeed(seed)?.publicKey }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Image(Icons.Outlined.InstallMobile, null, Modifier.size(58.dp), colorFilter = ColorFilter.tint(Theme.foreground))
        if (key != null) BasicText(model.displayName(key), Modifier.padding(top = 18.dp), style = Theme.caption(), maxLines = 1)
        Spacer(Modifier.weight(1f))
        GlassButton(Icons.Outlined.Fingerprint, "Usar esta billetera", Modifier.padding(bottom = 36.dp), enabled = !model.busy) {
            scope.launch { model.adopt(seed) }
        }
    }
}
