package com.nierduolong.morningbell.ui.dailylog

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.nierduolong.morningbell.dailylog.lan.NearbyInvite
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun InviteQrImage(
    invite: NearbyInvite,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(invite) { NearbyQrRenderer.render(invite.encode(), 480) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "加入附近 Log 的二维码",
        modifier = modifier.background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
    )
}

/** 直接复用项目已有 CameraX，不跳转第三方扫码 App，也不需要联网。 */
@Composable
internal fun NearbyInviteScannerDialog(
    onDecoded: (NearbyInvite) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

    DisposableEffect(previewView, lifecycleOwner) {
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val delivered = AtomicBoolean(false)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null

        providerFuture.addListener(
            {
                if (disposed) return@addListener
                runCatching {
                    val cameraProvider = providerFuture.get()
                    val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysisUseCase =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            android.util.Size(960, 720),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                        ),
                                    ).build(),
                            )
                            .build()
                            .also { useCase ->
                                useCase.setAnalyzer(
                                    analyzerExecutor,
                                    NearbyQrAnalyzer { raw ->
                                        val invite = NearbyInvite.parse(raw) ?: return@NearbyQrAnalyzer
                                        if (delivered.compareAndSet(false, true)) {
                                            previewView.post { if (!disposed) onDecoded(invite) }
                                        }
                                    },
                                )
                            }
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysisUseCase)
                    provider = cameraProvider
                    preview = previewUseCase
                    analysis = analysisUseCase
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            val useCases = listOfNotNull(preview, analysis).toTypedArray()
            if (useCases.isNotEmpty()) runCatching { provider?.unbind(*useCases) }
            analyzerExecutor.shutdownNow()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(top = 28.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭扫码")
                    }
                    Text("扫描房主的邀请二维码", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                }
                Text("二维码只在当前热点内生效", style = MaterialTheme.typography.bodySmall)
            }
            Box(
                Modifier
                    .size(260.dp)
                    .align(Alignment.Center)
                    .border(3.dp, androidx.compose.ui.graphics.Color.White, RoundedCornerShape(18.dp)),
            )
        }
    }
}

private object NearbyQrRenderer {
    fun render(
        value: String,
        size: Int,
    ): Bitmap {
        val matrix =
            QRCodeWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val row = IntArray(size)
        for (y in 0 until size) {
            for (x in 0 until size) row[x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            bitmap.setPixels(row, 0, size, 0, y, size, 1)
        }
        return bitmap
    }
}

private class NearbyQrAnalyzer(
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader =
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    private var lastAttemptAt = 0L
    private var luminanceBytes = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAttemptAt < 180L) return
            lastAttemptAt = now
            val required = image.width * image.height
            if (luminanceBytes.size != required) luminanceBytes = ByteArray(required)
            val source = image.yLuminanceSource(luminanceBytes) ?: return
            val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
            reader.reset()
            if (result != null) onDecoded(result.text)
        } finally {
            image.close()
        }
    }
}

private fun ImageProxy.yLuminanceSource(bytes: ByteArray): PlanarYUVLuminanceSource? {
    if (width <= 0 || height <= 0) return null
    if (bytes.size < width * height) return null
    val plane = planes.firstOrNull() ?: return null
    if (plane.pixelStride != 1 || plane.rowStride < width) return null
    val buffer = plane.buffer
    for (row in 0 until height) {
        val sourceOffset = row * plane.rowStride
        if (sourceOffset + width > buffer.limit()) return null
        buffer.position(sourceOffset)
        buffer.get(bytes, row * width, width)
    }
    return PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
}
