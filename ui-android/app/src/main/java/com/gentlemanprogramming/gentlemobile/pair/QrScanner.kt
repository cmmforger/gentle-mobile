package com.gentlemanprogramming.gentlemobile.pair

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.gentlemanprogramming.gentlemobile.ui.theme.LocalPalette
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * The Pair screen's QR affordance, per `design/gentle-companion/01-pair.html`:
 * scan the host's `gc://host:port?user=…` QR with CameraX preview + the
 * bundled ML Kit barcode model. The caller owns the camera permission and
 * the fallback — a denied or missing camera never reaches this screen, the
 * paste field is the path instead.
 *
 * `InputImage.fromMediaImage` is the (compile-time real) bridge from a
 * CameraX `ImageProxy` to ML Kit; it needs the `ExperimentalGetImage`
 * opt-in from camera-core, declared on the analyzer class.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private class QrAnalyzer(
    private val onQr: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private val executor = Executors.newSingleThreadExecutor()
    private var reported = false

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                if (reported) return@addOnSuccessListener
                barcodes.firstOrNull { it.rawValue != null }?.rawValue?.let { value ->
                    reported = true
                    onQr(value)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

/**
 * Full-screen camera scanner for the host's pair QR. One frame analysis
 * chain; it unbinds the camera when the screen goes away, so closing the
 * scanner never leaves the sensor on in the background.
 */
@Composable
fun QrScannerScreen(
    onQr: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalPalette.current.bg)
            .safeDrawingPadding(),
    ) {
        AndroidView(
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                            )
                            .build()
                        analysis.setAnalyzer(analysisExecutor, QrAnalyzer(onQr))
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (_: Exception) {
                        // No usable camera on this device: the paste field is
                        // the fallback, and the Cancel below still works.
                    }
                }, androidx.core.content.ContextCompat.getMainExecutor(viewContext))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Quiet overlay, consistent with the one-surface rule: a hint line
        // and one way out. No cards, no progress spinner.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Point the camera at the QR the host printed.",
                color = Color.White,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalPalette.current.accent),
            ) {
                Text("Cancel")
            }
        }
    }
}
