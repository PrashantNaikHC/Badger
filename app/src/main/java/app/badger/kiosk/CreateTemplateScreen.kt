package app.badger.kiosk

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    hasCamera: Boolean,
    onBack: () -> Unit,
    onSaveTemplate: (
        name: String,
        leftAnchor: String?,
        rightAnchor: String?,
        isPortrait: Boolean,
        relativeYMin: Float?,
        relativeYMax: Float?
    ) -> Unit
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    var templateName by rememberSaveable { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cardWordTokens by remember { mutableStateOf<List<SmartNameDetector.CardWordToken>>(emptyList()) }
    var isProcessingCapture by remember { mutableStateOf(false) }

    // Multi-word name selection indices (direct tap on image)
    var selectedWordIds by rememberSaveable { mutableStateOf(setOf<Int>()) }

    // Template name selection indices (direct long-press on image)
    var templateWordIds by rememberSaveable { mutableStateOf(setOf<Int>()) }

    // Live card detection and stability state
    var cardDetectionState by remember { mutableStateOf(CardDetectionState.NO_CARD) }
    var stabilityProgress by remember { mutableFloatStateOf(0f) }
    var showFlash by remember { mutableStateOf(false) }

    val latestFrameRef = remember { AtomicReference<Bitmap?>(null) }

    // Helper to process captured frame with ML Kit and prepare for visual name selection
    fun processCapturedData(bitmap: Bitmap, visionText: Text) {
        val tokens = SmartNameDetector.extractWordTokens(visionText)
        val suggestedName = SmartNameDetector.suggestTemplateName(tokens, bitmap.height)

        capturedBitmap = bitmap
        cardWordTokens = tokens
        selectedWordIds = emptySet()
        templateWordIds = emptySet()

        if (templateName.isBlank() && suggestedName != null) {
            templateName = suggestedName
        }

        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showFlash = true
        coroutineScope.launch {
            delay(150)
            showFlash = false
        }
        isProcessingCapture = false
    }

    // Auto-capture analyzer instance
    val autoCaptureAnalyzer = remember {
        AutoCaptureAnalyzer(
            onStateUpdate = { state, progress ->
                cardDetectionState = state
                stabilityProgress = progress
            },
            onCaptured = { bitmap, visionText ->
                coroutineScope.launch {
                    processCapturedData(bitmap, visionText)
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            autoCaptureAnalyzer.release()
        }
    }

    // Selected tokens in natural reading order
    val selectedTokensInOrder = remember(cardWordTokens, selectedWordIds) {
        cardWordTokens.filter { it.id in selectedWordIds }
    }

    val extractedCandidate = remember(selectedTokensInOrder) {
        if (selectedTokensInOrder.isNotEmpty()) {
            selectedTokensInOrder.joinToString(" ") { t ->
                t.text.lowercase(Locale.ROOT).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
        } else null
    }

    // Automatically compute anchors based on the selected name word(s) in reading order
    val minIdx = remember(cardWordTokens, selectedWordIds) {
        cardWordTokens.indexOfFirst { it.id in selectedWordIds }
    }
    val maxIdx = remember(cardWordTokens, selectedWordIds) {
        cardWordTokens.indexOfLast { it.id in selectedWordIds }
    }

    val leftAnchor = remember(minIdx, cardWordTokens) {
        if (minIdx > 0 && minIdx < cardWordTokens.size) {
            cardWordTokens[minIdx - 1].text
        } else null
    }

    val rightAnchor = remember(maxIdx, cardWordTokens) {
        if (maxIdx >= 0 && maxIdx < cardWordTokens.size - 1) {
            cardWordTokens[maxIdx + 1].text
        } else null
    }

    val detectedRelativeYMin = remember(selectedTokensInOrder, capturedBitmap) {
        val bmp = capturedBitmap
        if (selectedTokensInOrder.isNotEmpty() && bmp != null) {
            (selectedTokensInOrder.minOf { it.boundingBox.top }.toFloat() / bmp.height.toFloat()).coerceIn(0f, 1f)
        } else null
    }

    val detectedRelativeYMax = remember(selectedTokensInOrder, capturedBitmap) {
        val bmp = capturedBitmap
        if (selectedTokensInOrder.isNotEmpty() && bmp != null) {
            (selectedTokensInOrder.maxOf { it.boundingBox.bottom }.toFloat() / bmp.height.toFloat()).coerceIn(0f, 1f)
        } else null
    }

    val detectedIsPortrait = remember(capturedBitmap) {
        val bmp = capturedBitmap
        if (bmp != null) bmp.height >= bmp.width else true
    }

    Scaffold(
        containerColor = Color(0xFF101418),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (capturedBitmap == null) "Capture ID Card" else "Configure Template",
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101418)),
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (capturedBitmap != null) {
                        IconButton(onClick = {
                            capturedBitmap = null
                            cardWordTokens = emptyList()
                            selectedWordIds = emptySet()
                            templateWordIds = emptySet()
                            stabilityProgress = 0f
                            cardDetectionState = CardDetectionState.NO_CARD
                            autoCaptureAnalyzer.reset()
                        }) {
                            Icon(Icons.Default.Refresh, "Retake Photo", tint = Color.White)
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (capturedBitmap == null) {
            // Live back camera viewfinder with Stability & Auto-Capture
            Column(
                Modifier.fillMaxSize().padding(pad).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Hold the ID card steady inside the frame. It will auto-capture when clear.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black)
                ) {
                    if (hasCamera) {
                        BackCameraCaptureView(
                            onFrame = { frame ->
                                latestFrameRef.set(frame)
                                if (!isProcessingCapture) {
                                    autoCaptureAnalyzer.processFrame(frame)
                                }
                            }
                        )

                        // ID Card Targeting Reticle & Live Stability HUD
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val animatedProgress by animateFloatAsState(
                                targetValue = stabilityProgress,
                                animationSpec = tween(durationMillis = 150),
                                label = "stabilityProgress"
                            )

                            val reticleBorderColor by animateColorAsState(
                                targetValue = when (cardDetectionState) {
                                    CardDetectionState.NO_CARD -> Color.White.copy(alpha = 0.45f)
                                    CardDetectionState.CARD_DETECTED -> Color(0xFF00E5FF)
                                    CardDetectionState.HOLDING_STEADY, CardDetectionState.CAPTURED -> Color(0xFF00E676)
                                },
                                animationSpec = tween(durationMillis = 200),
                                label = "reticleBorderColor"
                            )

                            val reticleBorderWidth by animateDpAsState(
                                targetValue = if (cardDetectionState == CardDetectionState.HOLDING_STEADY) 3.5.dp else 2.5.dp,
                                animationSpec = tween(durationMillis = 200),
                                label = "reticleBorderWidth"
                            )

                            // Card aspect ratio container (approx 1 : 1.58 standard badge)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .aspectRatio(0.65f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(reticleBorderWidth, reticleBorderColor, RoundedCornerShape(16.dp))
                            )

                            // Top Stability & Presence Badge
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 18.dp),
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.82f),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, reticleBorderColor)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when (cardDetectionState) {
                                        CardDetectionState.NO_CARD -> {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(Color.White.copy(alpha = 0.5f), CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Position ID card in frame",
                                                color = Color.White.copy(alpha = 0.9f),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        CardDetectionState.CARD_DETECTED -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                color = Color(0xFF00E5FF),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Card detected · Hold steady",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        CardDetectionState.HOLDING_STEADY -> {
                                            CircularProgressIndicator(
                                                progress = { animatedProgress },
                                                modifier = Modifier.size(16.dp),
                                                color = Color(0xFF00E676),
                                                strokeWidth = 2.5.dp
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Hold steady… ${(animatedProgress * 100).toInt()}%",
                                                color = Color(0xFF00E676),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        CardDetectionState.CAPTURED -> {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color(0xFF00E676),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Captured!",
                                                color = Color(0xFF00E676),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            // Bottom Stability Progress Bar
                            if (cardDetectionState == CardDetectionState.HOLDING_STEADY || cardDetectionState == CardDetectionState.CARD_DETECTED) {
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 20.dp)
                                        .fillMaxWidth(0.68f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF00E676),
                                    trackColor = Color.White.copy(alpha = 0.25f)
                                )
                            }
                        }

                        // Shutter Flash Animation Overlay
                        val flashAlpha by animateFloatAsState(
                            targetValue = if (showFlash) 0.85f else 0f,
                            animationSpec = tween(durationMillis = if (showFlash) 50 else 180),
                            label = "flashAlpha"
                        )
                        if (flashAlpha > 0.01f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.White.copy(alpha = flashAlpha))
                            )
                        }
                    } else {
                        Text("Camera permission required", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Manual Capture Button as Instant Fallback
                Button(
                    onClick = {
                        val frame = latestFrameRef.get()
                        if (frame != null && !isProcessingCapture) {
                            isProcessingCapture = true
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            val image = InputImage.fromBitmap(frame, 0)
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    processCapturedData(frame, visionText)
                                }
                                .addOnFailureListener {
                                    isProcessingCapture = false
                                }
                        }
                    },
                    enabled = hasCamera && !isProcessingCapture,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isProcessingCapture) "Processing…" else "Capture Manually", fontSize = 16.sp)
                }
            }
        } else {
            // Visual Tap-on-Image Selection Mode
            val bitmap = capturedBitmap!!

            Column(
                Modifier.fillMaxSize().padding(pad)
            ) {
                // Main Interactive Photo Viewer
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1C242B))
                ) {
                    InteractiveCardViewer(
                        bitmap = bitmap,
                        tokens = cardWordTokens,
                        selectedIds = selectedWordIds,
                        templateIds = templateWordIds,
                        onToggle = { id ->
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            // If word was part of template name, remove it from template name
                            if (id in templateWordIds) {
                                templateWordIds = templateWordIds - id
                            }
                            selectedWordIds = if (id in selectedWordIds) {
                                selectedWordIds - id
                            } else {
                                selectedWordIds + id
                            }
                        },
                        onLongClick = { token ->
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            // Find all tokens on the same line as the long-pressed token
                            val lineTokens = cardWordTokens.filter { it.lineIndex == token.lineIndex }
                            val lineWordIds = lineTokens.map { it.id }.toSet()

                            // Set template word IDs and remove from employee name selection
                            templateWordIds = lineWordIds
                            selectedWordIds = selectedWordIds - lineWordIds

                            // Format template name to clean Title Case
                            val formattedName = lineTokens.joinToString(" ") { t ->
                                t.text.lowercase(Locale.ROOT).replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                                }
                            }
                            templateName = formattedName
                        }
                    )

                    // Floating Instruction Pill at the top of the photo
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.85f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when {
                                selectedWordIds.isNotEmpty() -> Color(0xFF00E676)
                                templateWordIds.isNotEmpty() -> Color(0xFF536DFE)
                                else -> Color(0xFFFFB74D)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    selectedWordIds.isNotEmpty() && templateWordIds.isNotEmpty() ->
                                        "✓ Name & Template Set · Tap to adjust"
                                    selectedWordIds.isNotEmpty() ->
                                        "✓ ${selectedTokensInOrder.size} word(s) name · Long press: Template"
                                    templateWordIds.isNotEmpty() ->
                                        "★ Template: $templateName · Tap name words"
                                    else ->
                                        "Tap: Select Name  ·  Long Press: Template Name"
                                },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Bottom Configuration Sheet
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    shadowElevation = 12.dp
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Extracted Name Preview Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (extractedCandidate != null) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                            )
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    "EXTRACTED EMPLOYEE NAME",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (extractedCandidate != null) Color(0xFF2E7D32) else Color(0xFFE65100),
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    extractedCandidate ?: "Tap the word(s) of the name on the ID card above",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (extractedCandidate != null) Color(0xFF1B5E20) else Color(0xFFBF360C)
                                )
                            }
                        }

                        // Template Name Field (auto-filled via long-tap or header detection)
                        OutlinedTextField(
                            value = templateName,
                            onValueChange = { templateName = it },
                            label = { Text("Template Name") },
                            placeholder = { Text("Long-press company text on card or type here") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        // Save Button
                        Button(
                            onClick = {
                                if (templateName.isNotBlank() && selectedWordIds.isNotEmpty() && (leftAnchor != null || rightAnchor != null)) {
                                    onSaveTemplate(
                                        templateName.trim(),
                                        leftAnchor,
                                        rightAnchor,
                                        detectedIsPortrait,
                                        detectedRelativeYMin,
                                        detectedRelativeYMax
                                    )
                                }
                            },
                            enabled = templateName.isNotBlank() && selectedWordIds.isNotEmpty() && (leftAnchor != null || rightAnchor != null),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save Template", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Interactive card viewer displaying the captured photo with touchable bounding boxes
 * overlaid over every recognized word.
 *
 * Tap: Selects / unselects employee name (Green).
 * Long Press: Selects line as template name (Indigo).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InteractiveCardViewer(
    bitmap: Bitmap,
    tokens: List<SmartNameDetector.CardWordToken>,
    selectedIds: Set<Int>,
    templateIds: Set<Int>,
    onToggle: (Int) -> Unit,
    onLongClick: (SmartNameDetector.CardWordToken) -> Unit
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        if (containerWidth <= 0 || containerHeight <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
            return@BoxWithConstraints
        }

        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val containerRatio = containerWidth / containerHeight

        val renderWidth: Float
        val renderHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (bitmapRatio > containerRatio) {
            renderWidth = containerWidth
            renderHeight = containerWidth / bitmapRatio
            offsetX = 0f
            offsetY = (containerHeight - renderHeight) / 2f
        } else {
            renderHeight = containerHeight
            renderWidth = containerHeight * bitmapRatio
            offsetX = (containerWidth - renderWidth) / 2f
            offsetY = 0f
        }

        val scaleX = renderWidth / bitmap.width.toFloat()
        val scaleY = renderHeight / bitmap.height.toFloat()

        // Card photo
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured ID Card",
            modifier = Modifier
                .size(
                    width = with(density) { renderWidth.toDp() },
                    height = with(density) { renderHeight.toDp() }
                )
                .offset(
                    x = with(density) { offsetX.toDp() },
                    y = with(density) { offsetY.toDp() }
                )
        )

        // Touchable Bounding Box Overlays
        tokens.forEach { token ->
            val isSelectedName = token.id in selectedIds
            val isTemplateWord = token.id in templateIds

            // Map token bounding box to container coordinates
            val left = offsetX + token.boundingBox.left * scaleX
            val top = offsetY + token.boundingBox.top * scaleY
            val right = offsetX + token.boundingBox.right * scaleX
            val bottom = offsetY + token.boundingBox.bottom * scaleY

            // Add touch padding for comfortable tapping
            val touchPaddingPx = with(density) { 4.dp.toPx() }
            val boxLeft = max(offsetX, left - touchPaddingPx)
            val boxTop = max(offsetY, top - touchPaddingPx)
            val boxWidth = max(with(density) { 28.dp.toPx() }, (right - left) + touchPaddingPx * 2f)
            val boxHeight = max(with(density) { 24.dp.toPx() }, (bottom - top) + touchPaddingPx * 2f)

            val boxBg = when {
                isSelectedName -> Color(0x554CAF50)
                isTemplateWord -> Color(0x553D5AFE)
                else -> Color(0x2200E5FF)
            }

            val boxBorderColor = when {
                isSelectedName -> Color(0xFF00E676)
                isTemplateWord -> Color(0xFF536DFE)
                else -> Color(0x9900E5FF)
            }

            val boxBorderWidth = if (isSelectedName || isTemplateWord) 2.5.dp else 1.dp

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { boxLeft.toDp() },
                        y = with(density) { boxTop.toDp() }
                    )
                    .size(
                        width = with(density) { boxWidth.toDp() },
                        height = with(density) { boxHeight.toDp() }
                    )
                    .clip(RoundedCornerShape(6.dp))
                    .background(boxBg)
                    .border(
                        width = boxBorderWidth,
                        color = boxBorderColor,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = { onToggle(token.id) },
                        onLongClick = { onLongClick(token) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isSelectedName -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF00E676),
                                modifier = Modifier.size(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.padding(1.dp)
                                )
                            }
                        }
                    }
                    isTemplateWord -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF536DFE),
                                modifier = Modifier.size(12.dp)
                            ) {
                                Text(
                                    "T",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
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

            var lastAnalysisTime = 0L

            analysis.setAnalyzer(executor) { proxy ->
                val now = System.currentTimeMillis()
                // Throttle image analysis to ~5 fps (every 180ms) to prevent memory allocation spikes
                if (now - lastAnalysisTime < 180L) {
                    proxy.close()
                    return@setAnalyzer
                }
                lastAnalysisTime = now

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
