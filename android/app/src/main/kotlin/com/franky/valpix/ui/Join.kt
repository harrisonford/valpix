package com.franky.valpix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.core.ValpixLink

/**
 * A phone with no account yet. It shows who it is; a member scans this to
 * bring it in, and the number appears once the chain says so.
 */
@Composable
fun JoinScreen(model: AppModel) {
    val copy = rememberCopy()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopLine(model)
        Spacer(Modifier.weight(1f))
        // Tap the code to copy it, to be brought in by a member who is somewhere else.
        val code = ValpixLink.Join(model.publicKey, model.sharedName).string
        QrImage(code, "Código para unirte", Modifier.size(250.dp), onClick = { copy(code) })
        Spacer(Modifier.weight(1f))
        // A wallet from another phone, or a backup, comes in through the same scanner.
        GlassButton(Icons.Outlined.QrCodeScanner, "Leer código", Modifier.padding(bottom = 36.dp)) { model.flow = AppModel.Flow.Scan }
    }
}
