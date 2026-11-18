package app.p2p

import android.util.Size
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InviteScanner(
    importResult: InviteImportState, close: () -> Unit, pickImage: () -> Unit,
    transferProfile: Boolean = false, scanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val latestResult by rememberUpdatedState(scanned)
    var error by remember { mutableStateOf(false) }
    var detected by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var wrongCode by remember { mutableStateOf(false) }
    val camera = remember(context) {
        runCatching {
            LifecycleCameraController(context).apply {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                imageAnalysisResolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            }
        }.getOrNull()
    }
    if (camera != null && !detected && !error) DisposableEffect(camera, lifecycle) {
        val active = AtomicBoolean(true)
        val disposed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        var lastScan = -180L
        camera.initializationFuture.addListener({
            if (!disposed.get() && runCatching { camera.initializationFuture.get() }.isFailure) error = true
        }, main)
        runCatching {
            camera.setImageAnalysisAnalyzer(executor) { frame ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (active.get() && now - lastScan >= 180L) {
                        lastScan = now
                        val value = runCatching { readCameraQr(frame) }.getOrNull()
                        val supported = value != null && if (transferProfile) isProfileTransferQr(value) else isSupportedInviteCode(value)
                        if (value != null && !supported) {
                            main.execute { if (!disposed.get() && active.get()) wrongCode = true }
                        } else if (value != null && active.compareAndSet(true, false)) {
                            main.execute {
                                if (!disposed.get()) {
                                    detected = true
                                    torch = false
                                    latestResult(value)
                                }
                            }
                        }
                    }
                } finally {
                    frame.close()
                }
            }
            camera.bindToLifecycle(lifecycle)
        }.onFailure { error = true }
        onDispose {
            disposed.set(true)
            active.set(false)
            runCatching { camera.enableTorch(false) }
            runCatching { camera.clearImageAnalysisAnalyzer() }
            runCatching { camera.unbind() }
            executor.shutdown()
        }
    }

    Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(
        securePolicy = if (transferProfile) androidx.compose.ui.window.SecureFlagPolicy.SecureOn
            else androidx.compose.ui.window.SecureFlagPolicy.Inherit
    )) {
        Surface(shape = P2pDialogShape) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val qrLabel = if (transferProfile) "device transfer QR" else "invite QR"
                Text("Scan $qrLabel", style = MaterialTheme.typography.titleLarge)
                if (error || camera == null) {
                    Text(if (transferProfile) "The camera couldn’t start. You can read a QR from an image."
                        else "The camera couldn’t start. You can paste an invite or read a QR from an image.")
                } else if (detected) {
                    Text("QR code detected", style = MaterialTheme.typography.titleMedium)
                    val message = importResult.message ?: if (transferProfile) "Receiving profile…" else "Checking the invite…"
                    Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (importResult.failed) MaterialTheme.colorScheme.error else LocalContentColor.current)
                    if (importResult.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp.coerceIn(140.dp, 300.dp)
                    Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { viewContext ->
                                PreviewView(viewContext).apply {
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    scaleType = PreviewView.ScaleType.FIT_CENTER
                                    controller = camera
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(Modifier.sizeIn(maxWidth = previewHeight * 0.82f).fillMaxWidth(0.76f).aspectRatio(1f)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
                    }
                    Text(if (transferProfile) {
                        if (wrongCode) "This isn’t a device transfer QR." else "Looking for a device transfer QR…"
                    } else {
                        if (wrongCode) "That QR code isn’t a supported invite." else "Looking for a supported invite…"
                    })
                    Text("Open the large QR on the other phone. Fit the whole code in the frame and tap to focus.")
                    TextButton(onClick = {
                        val target = !torch
                        runCatching { camera.enableTorch(target) }.onSuccess { torch = target }
                    }) { Text(if (torch) "Turn Off Flashlight" else "Turn On Flashlight") }
                }
                if (!detected || importResult.failed) TextButton(onClick = pickImage) { Text("Read QR from Image") }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (detected && importResult.failed) TextButton(onClick = { wrongCode = false; detected = false }) { Text("Scan Again") }
                    TextButton(onClick = close) {
                        Text(if (detected && !transferProfile) "Back to Accept Invite" else "Close")
                    }
                }
            }
        }
    }
}

private fun readCameraQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    val buffer = plane.buffer.duplicate()
    val start = buffer.position()
    val stride = plane.rowStride
    val step = plane.pixelStride
    if (width <= 0 || height <= 0 || step <= 0 ||
        start.toLong() + (height - 1L) * stride + (width - 1L) * step >= buffer.limit()) return null
    val luma = ByteArray(width * height)
    for (row in 0 until height) {
        val base = start + row * stride
        if (step == 1) {
            buffer.position(base)
            buffer.get(luma, row * width, width)
        } else {
            for (col in 0 until width) luma[row * width + col] = buffer.get(base + col * step)
        }
    }
    val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    return InviteImage.readSource(source, tryGlobal = true)
}
