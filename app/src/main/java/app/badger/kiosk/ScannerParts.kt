package app.badger.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.graphics.Matrix
import android.util.Size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun RegistrationScreen(
    hasCamera: Boolean,
    leftAnchor: String?,
    rightAnchor: String?,
    onOcrCandidate: (String?) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("New card detected", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Hold your ID card so the printed name faces the camera", color = Color.LightGray, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
            if (hasCamera) CameraOcrView(leftAnchor, rightAnchor, onOcrCandidate)
            else Text("Camera permission required", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel", color = Color.White, fontSize = 18.sp) }
    }
}

@Composable
fun CameraOcrView(
    leftAnchor: String?,
    rightAnchor: String?,
    onOcrCandidate: (String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback by rememberUpdatedState(onOcrCandidate)
    val frameCounter = remember { java.util.concurrent.atomic.AtomicInteger() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Bundled Latin model: no network needed.
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner, leftAnchor, rightAnchor) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get().also { provider = it }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            // Higher resolution than the 640x480 default: small printed text needs it.
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                // Alternate upright / horizontally mirrored frames, so the card is read
                // whichever way the front camera orientation ends up.
                val mirrored = frameCounter.getAndIncrement() % 2 == 1
                val bitmap = try {
                    val m = Matrix().apply {
                        postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                        if (mirrored) postScale(-1f, 1f)
                    }
                    val raw = proxy.toBitmap()
                    android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                } catch (e: Exception) { null }
                if (bitmap == null) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val lines = text.textBlocks.flatMap { it.lines }.map { it.text }
                        currentCallback(NameExtractor.extract(lines, leftAnchor, rightAnchor))
                    }
                    .addOnCompleteListener { proxy.close() }
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
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}


/** Idle prompt with a looping animation: an ID card swoops onto the TOP of the phone's BACK. */
@Composable
fun IdleTapScreen(prompt: String) {
    // Frame-clock driven loop (0..1 every 3.4s). Unlike animate*AsState/infinite transitions it does
    // NOT depend on the system "Animator duration scale", which kiosk devices often set to 0
    // (that froze the animation at its first frame, where the card was invisible).
    var t by remember { mutableFloatStateOf(0.5f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            t = (((now - start) / 1_000_000L) % 3400L) / 3400f
        }
    }
    val navy = Ink
    val accent = Accent

    Column(
        Modifier.fillMaxSize().background(IdleBg).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            prompt,
            color = navy, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, lineHeight = 40.sp
        )
        Spacer(Modifier.height(24.dp))

        Canvas(Modifier.size(width = 300.dp, height = 300.dp)) {
            val u = size.width / 300f // design units: 300 x 300
            fun o(x: Float, y: Float) = Offset(x * u, y * u)
            fun sz(w: Float, h: Float) = GeoSize(w * u, h * u)

            // Phone (back view)
            drawRoundRect(Color(0xFF263238), o(70f, 10f), sz(160f, 280f), CornerRadius(30f * u))
            drawRoundRect(Color(0xFF37474F), o(70f, 10f), sz(160f, 280f), CornerRadius(30f * u), style = Stroke(3f * u))
            // Camera bump (top-left of the back)
            drawRoundRect(Color(0xFF111A1F), o(82f, 26f), sz(50f, 56f), CornerRadius(16f * u))
            drawCircle(Color(0xFF455A64), 10f * u, o(99f, 44f))
            drawCircle(Color(0xFF455A64), 10f * u, o(115f, 64f))
            // NFC antenna zone: right next to the camera, top of the phone
            val cx = 178f
            val hy = 70f
            val target = o(cx, hy)
            drawCircle(accent.copy(alpha = 0.15f), 32f * u, target)

            // Timeline: 0-.25 approach, .25-.88 hold + waves, .88-1 fade out
            val approach = (t / 0.25f).coerceIn(0f, 1f)
            val leave = ((t - 0.88f) / 0.12f).coerceIn(0f, 1f)
            val ease = 1f - (1f - approach) * (1f - approach)
            val cy = hy + (1f - ease) * 150f
            val tilt = (1f - ease) * -18f
            val alpha = (approach.coerceAtMost(1f) * (1f - leave)).coerceIn(0f, 1f)

            // Ripples while the card is held in place
            if (t in 0.25f..0.9f) {
                val hold = (t - 0.25f) / 0.65f
                for (i in 0 until 3) {
                    val ph = (hold * 2.2f + i / 3f) % 1f
                    drawCircle(accent.copy(alpha = (1f - ph) * 0.8f), (28f + ph * 60f) * u, target, style = Stroke(4f * u))
                }
            }

            // Vertical ID card (portrait, with lanyard slot, photo and text lines)
            rotate(tilt, pivot = o(cx, cy)) {
                val w = 66f
                val h = 98f
                val topLeft = o(cx - w / 2, cy - h / 2)
                val grey = Color(0xFFB0BEC5).copy(alpha = alpha)
                drawRoundRect(Color.Black.copy(alpha = 0.18f * alpha), topLeft + Offset(3f * u, 5f * u), sz(w, h), CornerRadius(8f * u))
                drawRoundRect(Color.White.copy(alpha = alpha), topLeft, sz(w, h), CornerRadius(8f * u))
                drawRoundRect(accent.copy(alpha = alpha), topLeft, sz(w, 24f), CornerRadius(8f * u))
                // lanyard slot
                drawRoundRect(Color.White.copy(alpha = alpha), topLeft + Offset((w / 2 - 9f) * u, 6f * u), sz(18f, 5f), CornerRadius(2.5f * u))
                // photo
                drawCircle(grey, 13f * u, topLeft + Offset(w / 2 * u, 46f * u))
                // name lines
                drawRoundRect(grey, topLeft + Offset(12f * u, 68f * u), sz(w - 24f, 6f), CornerRadius(3f * u))
                drawRoundRect(Color(0xFFCFD8DC).copy(alpha = alpha), topLeft + Offset(18f * u, 80f * u), sz(w - 36f, 6f), CornerRadius(3f * u))
            }
        }
    }
}
