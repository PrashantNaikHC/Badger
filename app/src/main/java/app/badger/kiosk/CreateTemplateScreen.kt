package app.badger.kiosk

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    hasCamera: Boolean,
    onBack: () -> Unit,
    onSaveTemplate: (name: String, leftAnchor: String?, rightAnchor: String?) -> Unit
) {
    var templateName by rememberSaveable { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedTokens by remember { mutableStateOf<List<String>>(emptyList()) }
    var isProcessingCapture by remember { mutableStateOf(false) }

    // Multi-word name selection indices (continuous range: selected indices)
    var selectedWordIndices by rememberSaveable { mutableStateOf(setOf<Int>()) }

    val latestFrameRef = remember { AtomicReference<Bitmap?>(null) }

    // Sorted selected indices
    val minIdx = selectedWordIndices.minOrNull()
    val maxIdx = selectedWordIndices.maxOrNull()

    // Automatically compute anchors based on the selected name word(s)
    val leftAnchor = remember(minIdx, detectedTokens) {
        if (minIdx != null && minIdx > 0 && minIdx <= detectedTokens.size) {
            detectedTokens[minIdx - 1]
        } else null
    }

    val rightAnchor = remember(maxIdx, detectedTokens) {
        if (maxIdx != null && maxIdx >= 0 && maxIdx < detectedTokens.size - 1) {
            detectedTokens[maxIdx + 1]
        } else null
    }

    // Live preview of candidate extracted name
    val extractedCandidate = remember(detectedTokens, leftAnchor, rightAnchor) {
        if (detectedTokens.isNotEmpty() && selectedWordIndices.isNotEmpty()) {
            NameExtractor.extract(detectedTokens, leftAnchor, rightAnchor)
        } else null
    }

    Scaffold(
        containerColor = IdleBg,
        topBar = {
            TopAppBar(
                title = { Text(if (capturedBitmap == null) "Capture ID Card" else "Select Employee Name") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        bottomBar = {
            if (capturedBitmap != null) {
                Surface(color = Color.White, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Button(
                            onClick = {
                                if (templateName.isNotBlank() && selectedWordIndices.isNotEmpty() && (leftAnchor != null || rightAnchor != null)) {
                                    onSaveTemplate(templateName.trim(), leftAnchor, rightAnchor)
                                }
                            },
                            enabled = templateName.isNotBlank() && selectedWordIndices.isNotEmpty() && (leftAnchor != null || rightAnchor != null),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("Save Template", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    ) { pad ->
        if (capturedBitmap == null) {
            // Live back camera viewfinder
            Column(
                Modifier.fillMaxSize().padding(pad).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Position the employee ID card inside the frame, then tap Freeze / Capture.",
                    color = Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black)
                ) {
                    if (hasCamera) {
                        BackCameraCaptureView(onFrame = { latestFrameRef.set(it) })
                    } else {
                        Text("Camera permission required", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        val frame = latestFrameRef.get()
                        if (frame != null && !isProcessingCapture) {
                            isProcessingCapture = true
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            val image = InputImage.fromBitmap(frame, 0)
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    val tokens = visionText.textBlocks.flatMap { it.lines }
                                        .flatMap { it.text.trim().split(Regex("\\s+")) }
                                        .map { it.trim(',', ':', '|', '.', '-', ';') }
                                        .filter { it.isNotEmpty() }

                                    detectedTokens = tokens
                                    capturedBitmap = frame
                                    selectedWordIndices = emptySet()
                                    isProcessingCapture = false
                                }
                                .addOnFailureListener {
                                    isProcessingCapture = false
                                }
                        }
                    },
                    enabled = hasCamera && !isProcessingCapture,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isProcessingCapture) "Processing OCR…" else "Freeze / Capture ID Card", fontSize = 16.sp)
                }
            }
        } else {
            // Captured state: Simple tap-to-select word UI with automatic anchor deduction
            Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Header & Retake
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Employee Name", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    OutlinedButton(
                        onClick = {
                            capturedBitmap = null
                            detectedTokens = emptyList()
                            selectedWordIndices = emptySet()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retake Photo", fontSize = 13.sp)
                    }
                }

                // Template Name Field
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text("Template Name") },
                    placeholder = { Text("e.g. Standard Company Badge") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Clean Live Preview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (extractedCandidate != null) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "EXTRACTED EMPLOYEE NAME",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (extractedCandidate != null) Color(0xFF2E7D32) else Color(0xFFE65100),
                            letterSpacing = 0.8.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            extractedCandidate ?: "Tap the word(s) below that represent the person's name",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (extractedCandidate != null) Color(0xFF1B5E20) else Color(0xFFBF360C)
                        )
                        if (leftAnchor != null || rightAnchor != null) {
                            Spacer(Modifier.height(4.dp))
                            val startDesc = leftAnchor?.let { "After “$it”" } ?: "Starts from top"
                            val endDesc = rightAnchor?.let { "Before “$it”" } ?: "Continues to bottom"
                            Text(
                                "Auto Anchors: $startDesc  ·  $endDesc",
                                fontSize = 12.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Clean Word Column Card (Just tap the name!)
                Card(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "TAP WORD(S) OF THE EMPLOYEE'S NAME",
                                color = Muted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            if (selectedWordIndices.isNotEmpty()) {
                                TextButton(
                                    onClick = { selectedWordIndices = emptySet() },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Clear Selection", fontSize = 11.sp, color = Ink)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        if (detectedTokens.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No words detected. Retake with better lighting.", color = Muted)
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(detectedTokens) { idx, word ->
                                    val isSelectedName = idx in selectedWordIndices
                                    val isStartAnchor = (minIdx != null && minIdx > 0 && idx == minIdx - 1)
                                    val isEndAnchor = (maxIdx != null && maxIdx < detectedTokens.size - 1 && idx == maxIdx + 1)

                                    val animatedBg by animateColorAsState(
                                        targetValue = when {
                                            isSelectedName -> Color(0xFFE8F5E9) // Selected name green
                                            isStartAnchor -> Color(0xFFE8EAF6) // Auto start anchor indigo
                                            isEndAnchor -> Color(0xFFFFF3E0) // Auto end anchor amber
                                            else -> Color(0xFFF8F9FA)
                                        },
                                        animationSpec = spring(stiffness = 500f),
                                        label = "WordRowBg"
                                    )

                                    val borderModifier = when {
                                        isSelectedName -> Modifier.border(2.dp, Color(0xFF2E7D32), RoundedCornerShape(10.dp))
                                        isStartAnchor -> Modifier.border(1.5.dp, Ink, RoundedCornerShape(10.dp))
                                        isEndAnchor -> Modifier.border(1.5.dp, Color(0xFFE65100), RoundedCornerShape(10.dp))
                                        else -> Modifier
                                    }

                                    Surface(
                                        color = animatedBg,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .then(borderModifier)
                                            .clickable {
                                                // Toggle word selection
                                                selectedWordIndices = if (selectedWordIndices.contains(idx)) {
                                                    selectedWordIndices - idx
                                                } else {
                                                    // When selecting multiple words, fill the range if there's an existing selection
                                                    if (selectedWordIndices.isNotEmpty()) {
                                                        val newMin = minOf(selectedWordIndices.minOrNull() ?: idx, idx)
                                                        val newMax = maxOf(selectedWordIndices.maxOrNull() ?: idx, idx)
                                                        (newMin..newMax).toSet()
                                                    } else {
                                                        setOf(idx)
                                                    }
                                                }
                                            }
                                    ) {
                                        Row(
                                            Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isSelectedName) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = Color(0xFF2E7D32),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(
                                                    word,
                                                    fontSize = 15.sp,
                                                    fontWeight = if (isSelectedName || isStartAnchor || isEndAnchor) FontWeight.Bold else FontWeight.Medium,
                                                    color = when {
                                                        isSelectedName -> Color(0xFF1B5E20)
                                                        isStartAnchor -> Ink
                                                        isEndAnchor -> Color(0xFFE65100)
                                                        else -> Color(0xFF37474F)
                                                    }
                                                )
                                            }

                                            when {
                                                isSelectedName -> {
                                                    Text("NAME", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2E7D32))
                                                }
                                                isStartAnchor -> {
                                                    Text("START ANCHOR (AUTO)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Ink)
                                                }
                                                isEndAnchor -> {
                                                    Text("END ANCHOR (AUTO)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun BackCameraCaptureView(onFrame: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCallback by rememberUpdatedState(onFrame)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
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
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val m = Matrix().apply {
                        postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                    }
                    val raw = proxy.toBitmap()
                    val bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    currentCallback(bitmap)
                } catch (e: Exception) {
                    // Ignore transient frame conversion errors
                } finally {
                    proxy.close()
                }
            }
            // Dedicated Back Camera for Admin template scanning
            val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
