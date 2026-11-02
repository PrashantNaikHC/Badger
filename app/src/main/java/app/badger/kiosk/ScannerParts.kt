package app.badger.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

@Composable
fun RegistrationScreen(
    hasCamera: Boolean,
    template: IdCardTemplateEntity?,
    onOcrCandidate: (String?) -> Unit,
    onCancel: () -> Unit
) {
    val isPortrait = template?.isPortrait ?: true

    Column(
        Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("New card detected", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (isPortrait) "Hold card vertically facing the camera"
            else "Hold card horizontally facing the camera",
            color = Color.LightGray,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            if (hasCamera) {
                CameraOcrView(template = template, onOcrCandidate = onOcrCandidate)
            } else {
                Text("Camera permission required", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel", color = Color.White, fontSize = 18.sp) }
    }
}

@Composable
fun CameraOcrView(
    template: IdCardTemplateEntity?,
    onOcrCandidate: (String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val currentCallback by rememberUpdatedState(onOcrCandidate)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    val leftAnchor = template?.leftAnchor
    val rightAnchor = template?.rightAnchor
    val isPortrait = template?.isPortrait ?: true

    // UI state for verification feedback
    var statusText by remember { mutableStateOf("Hold card facing front camera") }
    var statusColor by remember { mutableStateOf(Color.White) }
    var registrationProgress by remember { mutableFloatStateOf(0f) }

    // Consensus & stillness state
    val candidateNameRef = remember { AtomicReference<String?>(null) }
    val candidateCountRef = remember { AtomicInteger(0) }
    val lastCandidateTimeRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val isFinalizedRef = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner, leftAnchor, rightAnchor) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get().also { provider = it }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            var lastAnalysisTime = 0L
            var previousLuminance: IntArray? = null
            val sampleW = 80
            val sampleH = 60

            analysis.setAnalyzer(executor) { proxy ->
                if (isFinalizedRef.get()) {
                    proxy.close()
                    return@setAnalyzer
                }

                val now = System.currentTimeMillis()
                // Throttle analysis to ~5 fps (every 180ms)
                if (now - lastAnalysisTime < 180L) {
                    proxy.close()
                    return@setAnalyzer
                }
                lastAnalysisTime = now

                val fullBitmap = try {
                    val m = Matrix().apply {
                        postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                    }
                    val raw = proxy.toBitmap()
                    android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                } catch (e: Exception) { null }

                if (fullBitmap == null) {
                    proxy.close()
                    return@setAnalyzer
                }

                // 1. Pixel Stillness calculation (Mean Absolute Difference on downsampled luminance)
                val scaled = Bitmap.createScaledBitmap(fullBitmap, sampleW, sampleH, false)
                val pixels = IntArray(sampleW * sampleH)
                scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
                if (scaled != fullBitmap) scaled.recycle()

                val luma = IntArray(pixels.size)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    luma[i] = (r * 77 + g * 150 + b * 29) shr 8
                }

                val prevLuma = previousLuminance
                previousLuminance = luma

                val isStill = if (prevLuma != null) {
                    var sumDelta = 0L
                    for (i in luma.indices) {
                        sumDelta += kotlin.math.abs(luma[i] - prevLuma[i])
                    }
                    (sumDelta.toFloat() / luma.size.toFloat()) < 26.0f
                } else false

                // Helper to handle candidate consensus
                fun handleCandidateName(extracted: String?) {
                    if (isFinalizedRef.get()) return

                    if (!extracted.isNullOrBlank()) {
                        val prevCandidate = candidateNameRef.get()
                        val isSameCandidate = prevCandidate != null && prevCandidate.equals(extracted, ignoreCase = true)

                        if (isStill && isSameCandidate) {
                            val count = candidateCountRef.incrementAndGet()
                            lastCandidateTimeRef.set(now)

                            if (count >= 2) {
                                isFinalizedRef.set(true)
                                mainHandler.post {
                                    statusText = "Confirmed: $extracted"
                                    statusColor = Color(0xFF00E676)
                                    registrationProgress = 1f
                                    currentCallback(extracted)
                                }
                            }
                        } else {
                            // New or first candidate
                            candidateNameRef.set(extracted)
                            candidateCountRef.set(1)
                            lastCandidateTimeRef.set(now)

                            mainHandler.post {
                                statusText = "Card detected · Hold steady…"
                                statusColor = Color(0xFF00E5FF)
                                registrationProgress = 0.5f
                            }
                        }
                    } else {
                        // Only reset consensus if no card match has occurred for over 1.2 seconds
                        if (now - lastCandidateTimeRef.get() > 1200L && candidateCountRef.get() > 0) {
                            candidateNameRef.set(null)
                            candidateCountRef.set(0)
                            mainHandler.post {
                                statusText = "Hold card facing front camera"
                                statusColor = Color.White
                                registrationProgress = 0f
                            }
                        }
                    }
                }

                val image = InputImage.fromBitmap(fullBitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
                        val extracted = NameExtractor.extract(lines, leftAnchor, rightAnchor, allowFallback = true)

                        if (!extracted.isNullOrBlank()) {
                            handleCandidateName(extracted)
                            return@addOnSuccessListener
                        }

                        // Fallback check: in case front camera sensor output is mirrored
                        val mirrorMatrix = Matrix().apply {
                            postScale(-1f, 1f, fullBitmap.width / 2f, fullBitmap.height / 2f)
                        }
                        val mirroredBitmap = try {
                            android.graphics.Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, mirrorMatrix, true)
                        } catch (e: Exception) { null }

                        if (mirroredBitmap != null) {
                            val mirrorImage = InputImage.fromBitmap(mirroredBitmap, 0)
                            recognizer.process(mirrorImage)
                                .addOnSuccessListener { mirrorText ->
                                    val mirrorLines = mirrorText.textBlocks.flatMap { it.lines }.map { it.text }
                                    val mirrorExtracted = NameExtractor.extract(mirrorLines, leftAnchor, rightAnchor, allowFallback = true)
                                    handleCandidateName(mirrorExtracted)
                                }
                                .addOnCompleteListener {
                                    mirroredBitmap.recycle()
                                }
                        } else {
                            handleCandidateName(null)
                        }
                    }
                    .addOnCompleteListener {
                        proxy.close()
                    }
            }

            val selector = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            recognizer.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Visual Orientation & Card Reticle Guide
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val cardAspectRatio = if (isPortrait) 0.65f else 1.55f

            Box(
                modifier = Modifier
                    .fillMaxWidth(if (isPortrait) 0.82f else 0.90f)
                    .aspectRatio(cardAspectRatio)
                    .border(2.5.dp, statusColor, RoundedCornerShape(16.dp))
            )

            // Status badge
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.78f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (registrationProgress in 0.1f..0.9f) {
                        CircularProgressIndicator(
                            progress = { registrationProgress },
                            modifier = Modifier.size(14.dp),
                            color = statusColor,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    } else if (registrationProgress >= 1f) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bottom Progress Bar
            if (registrationProgress > 0f) {
                LinearProgressIndicator(
                    progress = { registrationProgress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .fillMaxWidth(0.68f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = statusColor,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
        }
    }
}

/** Idle prompt with a looping animation: an ID card swoops onto the TOP of the phone's BACK. */
@Composable
fun IdleTapScreen(prompt: String) {
    var t by remember { mutableFloatStateOf(0.5f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            t = (((now - start) / 1_000_000L) % 3400L) / 3400f
            kotlinx.coroutines.delay(16)
        }
    }

    val accent = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F171A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                prompt,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Hold your NFC card or badge near the top back of the device",
                color = Color(0xFF90A4AE),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            PhoneBackTapIllustration(t, accent)
        }
    }
}

@Composable
private fun PhoneBackTapIllustration(t: Float, accent: Color) {
    Canvas(Modifier.size(300.dp, 300.dp)) {
        val u = size.minDimension / 300f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun sz(w: Float, h: Float) = GeoSize(w * u, h * u)

        // Phone body
        drawRoundRect(Color(0xFF1C272C), o(70f, 10f), sz(160f, 280f), CornerRadius(30f * u))
        drawRoundRect(Color(0xFF37474F), o(70f, 10f), sz(160f, 280f), CornerRadius(30f * u), style = Stroke(3f * u))
        // Camera bump
        drawRoundRect(Color(0xFF111A1F), o(82f, 26f), sz(50f, 56f), CornerRadius(16f * u))
        drawCircle(Color(0xFF455A64), 10f * u, o(99f, 44f))
        drawCircle(Color(0xFF455A64), 10f * u, o(115f, 64f))

        // NFC antenna zone
        val cx = 178f
        val hy = 70f
        val target = o(cx, hy)
        drawCircle(accent.copy(alpha = 0.15f), 32f * u, target)

        val approach = (t / 0.25f).coerceIn(0f, 1f)
        val leave = ((t - 0.88f) / 0.12f).coerceIn(0f, 1f)
        val ease = 1f - (1f - approach) * (1f - approach)
        val cy = hy + (1f - ease) * 150f
        val tilt = (1f - ease) * -18f
        val alpha = (approach.coerceAtMost(1f) * (1f - leave)).coerceIn(0f, 1f)

        if (t in 0.25f..0.9f) {
            val hold = (t - 0.25f) / 0.65f
            for (i in 0 until 3) {
                val ph = (hold * 2.2f + i / 3f) % 1f
                drawCircle(accent.copy(alpha = (1f - ph) * 0.8f), (28f + ph * 60f) * u, target, style = Stroke(4f * u))
            }
        }

        rotate(tilt, pivot = o(cx, cy)) {
            val w = 66f
            val h = 98f
            val topLeft = o(cx - w / 2, cy - h / 2)
            val grey = Color(0xFFB0BEC5).copy(alpha = alpha)
            drawRoundRect(Color.Black.copy(alpha = 0.18f * alpha), topLeft + Offset(3f * u, 5f * u), sz(w, h), CornerRadius(8f * u))
            drawRoundRect(Color.White.copy(alpha = alpha), topLeft, sz(w, h), CornerRadius(8f * u))
            drawRoundRect(accent.copy(alpha = alpha), topLeft, sz(w, 24f), CornerRadius(8f * u))
            drawRoundRect(Color.White.copy(alpha = alpha), topLeft + Offset((w / 2 - 9f) * u, 6f * u), sz(18f, 5f), CornerRadius(2.5f * u))
            drawCircle(grey, 13f * u, topLeft + Offset(w / 2 * u, 46f * u))
            drawRoundRect(grey, topLeft + Offset(12f * u, 68f * u), sz(w - 24f, 6f), CornerRadius(3f * u))
            drawRoundRect(Color(0xFFCFD8DC).copy(alpha = alpha), topLeft + Offset(18f * u, 80f * u), sz(w - 36f, 6f), CornerRadius(3f * u))
        }
    }
}
