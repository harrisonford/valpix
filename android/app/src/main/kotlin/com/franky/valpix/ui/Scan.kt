package com.franky.valpix.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.franky.valpix.AppModel
import com.franky.valpix.GlassButton
import com.franky.valpix.core.LinkSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** The camera, looking for a valpix code. Anything else is ignored. */
@Composable
fun ScanScreen(model: AppModel) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (allowed) {
            CameraPreview(Modifier.fillMaxSize().semantics { contentDescription = "Cámara" }) { text ->
                model.open(text, LinkSource.SCANNED).also { if (it) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
        }
        // A code can also arrive as text: a link someone sent.
        GlassButton(Icons.Outlined.ContentPaste, "Pegar código", Modifier.padding(bottom = 36.dp), size = 52.dp) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                ?.let { model.open(it, LinkSource.SCANNED) }
        }
    }
}

/** A live camera view that reports each QR code it reads, until one is accepted. */
@Composable
private fun CameraPreview(modifier: Modifier, onCode: (String) -> Boolean) {
    val context = LocalContext.current
    val owner = context as LifecycleOwner
    val worker = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }
    var finished by remember { mutableStateOf(false) }

    AndroidView(modifier = modifier, factory = { viewContext ->
        PreviewView(viewContext).also { view ->
            val pending = ProcessCameraProvider.getInstance(viewContext)
            pending.addListener({
                // A phone with no camera simply shows black.
                runCatching {
                    val provider = pending.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build()
                        )
                        .build()
                    analysis.setAnalyzer(worker, QrAnalyzer { text ->
                        main.execute { if (!finished && onCode(text)) finished = true }
                    })
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, main)
        }
    })
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            worker.shutdown()
        }
    }
}

/** Looks for a QR code in each camera frame's brightness plane. */
private class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: NotFoundException) {
                // A code shown white on black, as some screens do.
                try {
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
                } catch (e: NotFoundException) {
                    null
                }
            }
            text?.let(onCode)
        } catch (e: RuntimeException) {
            // A frame we could not read is just a frame.
        } finally {
            reader.reset()
            image.close()
        }
    }
}
