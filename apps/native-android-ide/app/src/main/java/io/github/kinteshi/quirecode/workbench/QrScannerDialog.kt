package io.github.kinteshi.quirecode.workbench

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Decodes a QR code from tightly packed 8-bit luminance [lumaBytes] and accepts only `avf://` connection strings (G36). */
internal fun decodeQrConnectionString(lumaBytes: ByteArray, width: Int, height: Int): QrScanResult? {
    if (width <= 0 || height <= 0 || lumaBytes.size < width * height) return null
    val source = PlanarYUVLuminanceSource(lumaBytes, width, height, 0, 0, width, height, false)
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    val reader = MultiFormatReader().apply { setHints(hints) }
    fun decode(candidate: com.google.zxing.LuminanceSource): String? = runCatching {
        reader.decode(BinaryBitmap(HybridBinarizer(candidate))).text
    }.getOrNull()
    val raw = decode(source) ?: decode(source.invert()) ?: return null
    val parsed = extractConnectionStringFromText(raw)
    return if (parsed != null) QrScanResult.Connection(parsed) else QrScanResult.OtherQr
}

internal sealed interface QrScanResult {
    data class Connection(val value: String) : QrScanResult
    data object OtherQr : QrScanResult
}

internal fun extractLumaBytes(image: ImageProxy): ByteArray {
    val plane = image.planes[0]
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val buffer: ByteBuffer = plane.buffer.duplicate().apply { rewind() }
    val out = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width && buffer.remaining() >= out.size) {
        buffer.get(out)
        return out
    }
    val row = ByteArray(rowStride)
    for (y in 0 until height) {
        val available = buffer.remaining().coerceAtMost(rowStride)
        buffer.get(row, 0, available)
        val destOffset = y * width
        if (pixelStride == 1) {
            System.arraycopy(row, 0, out, destOffset, width.coerceAtMost(available))
        } else {
            for (x in 0 until width) {
                val srcIndex = x * pixelStride
                out[destOffset + x] = if (srcIndex < available) row[srcIndex] else 0
            }
        }
    }
    return out
}

/**
 * In-app QR scanner for `avf://` connection strings printed by `quirecode-daemon pair` (G36).
 * Requests `CAMERA` permission only when opened.
 */
@Composable
internal fun QrScannerDialog(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnScanned by rememberUpdatedState(onScanned)
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var nonConnectionNotice by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan pairing QR code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Point the camera at the QR code printed by `quirecode-daemon pair --remote` or `--tunnel`.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    !hasPermission -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "Camera permission is needed only to scan pairing QR codes; you can still paste the connection string without it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                        Text("Allow camera")
                                    }
                                    TextButton(onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(intent) }
                                    }) { Text("App info") }
                                }
                            }
                        }
                    }
                    cameraError != null -> {
                        Text(cameraError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        val previewView = remember { PreviewView(context) }
                        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
                        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
                        var handled by remember { mutableStateOf(false) }

                        DisposableEffect(lifecycleOwner, previewView) {
                            val providerFuture = ProcessCameraProvider.getInstance(context)
                            var cameraProvider: ProcessCameraProvider? = null
                            providerFuture.addListener({
                                runCatching {
                                    val provider = providerFuture.get()
                                    cameraProvider = provider
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analysis.setAnalyzer(analysisExecutor) { image ->
                                        try {
                                            if (handled) return@setAnalyzer
                                            val result = decodeQrConnectionString(
                                                extractLumaBytes(image),
                                                image.width,
                                                image.height,
                                            )
                                            when (result) {
                                                is QrScanResult.Connection -> {
                                                    handled = true
                                                    mainExecutor.execute { latestOnScanned(result.value) }
                                                }
                                                QrScanResult.OtherQr -> {
                                                    mainExecutor.execute { nonConnectionNotice = true }
                                                }
                                                null -> Unit
                                            }
                                        } finally {
                                            image.close()
                                        }
                                    }
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis,
                                    )
                                }.onFailure { error ->
                                    cameraError = error.message ?: "Couldn't open the camera"
                                }
                            }, mainExecutor)

                            onDispose {
                                cameraProvider?.unbindAll()
                                analysisExecutor.shutdown()
                            }
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(MaterialTheme.shapes.large),
                            contentAlignment = Alignment.Center,
                        ) {
                            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                        }
                        if (nonConnectionNotice) {
                            Text(
                                "Found a QR code, but it isn't a QuireCode connection string (avf://…).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
