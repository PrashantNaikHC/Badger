package app.badger.kiosk

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

enum class CardDetectionState {
    NO_CARD,          // Empty scene or no readable text detected
    CARD_DETECTED,     // ID card text detected, waiting for stillness
    HOLDING_STEADY,    // Card detected and still, stability progress filling
    CAPTURED           // Successfully auto-captured
}

/**
 * Real-time camera analyzer for template creation.
 *
 * Handles:
 * 1. Card Presence Check: Ensures an actual ID card with legible printed text (>= 3 words)
 *    is present in the frame. If the camera is still but pointing at a blank desk or wall,
 *    it will NOT falsely trigger.
 * 2. Pixel Stillness: Downsampled luminance frame differencing to detect camera/card motion.
 * 3. Text Consistency: Verifies that recognized text tokens remain stable across consecutive passes.
 * 4. Dispatches live progress (0f..1f) and state updates directly to the Main UI Thread.
 */
class AutoCaptureAnalyzer(
    private val onStateUpdate: (CardDetectionState, Float) -> Unit,
    private val onCaptured: (Bitmap, Text) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val isCapturing = AtomicBoolean(false)

    // Downsampled grid size for fast luminance analysis (80x60 = 4,800 pixels)
    private val sampleWidth = 80
    private val sampleHeight = 60
    private var previousLuminance: IntArray? = null

    // OCR throttling and consistency
    private var lastOcrTimestamp = 0L
    private val ocrIntervalMs = 200L
    private var isOcrRunning = false
    private var lastTokens: Set<String>? = null

    // Stability tracking: requires 3 consecutive stable passes (~0.7s to 0.9s of steady hold)
    private var stableConsecutivePasses = 0
    private val targetStablePasses = 3

    fun reset() {
        isCapturing.set(false)
        previousLuminance = null
        lastTokens = null
        stableConsecutivePasses = 0
        dispatchState(CardDetectionState.NO_CARD, 0f)
    }

    private fun dispatchState(state: CardDetectionState, progress: Float) {
        mainHandler.post {
            onStateUpdate(state, progress)
        }
    }

    fun processFrame(bitmap: Bitmap) {
        if (isCapturing.get()) return

        val now = System.currentTimeMillis()
        if (isOcrRunning || (now - lastOcrTimestamp) < ocrIntervalMs) {
            return
        }

        // 1. Measure pixel delta (stillness) on a downsampled grid
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, false)
        val pixels = IntArray(sampleWidth * sampleHeight)
        scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        if (scaled != bitmap) scaled.recycle()

        val luminance = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luminance[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        val prev = previousLuminance
        previousLuminance = luminance

        val isStill: Boolean
        if (prev != null) {
            var sumDelta = 0L
            for (i in luminance.indices) {
                sumDelta += abs(luminance[i] - prev[i])
            }
            val meanDelta = sumDelta.toFloat() / luminance.size.toFloat()
            // < 20 means relatively still hand/card
            isStill = meanDelta < 22.0f
        } else {
            isStill = false
        }

        // 2. Trigger OCR to check for actual ID card presence & text stability
        isOcrRunning = true
        lastOcrTimestamp = now

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (isCapturing.get()) return@addOnSuccessListener

                // Extract words from visionText
                val tokens = visionText.textBlocks.flatMap { it.lines }
                    .flatMap { it.text.trim().split(Regex("\\s+")) }
                    .map { it.trim(',', ':', '|', '.', '-', ';', '/', '(', ')') }
                    .filter { it.length >= 2 }
                    .map { it.uppercase() }
                    .toSet()

                // Edge Case Guard: Ensure an actual card is present!
                // An empty wall, table, or ceiling will have 0 or very few tokens.
                // An ID card typically has employee name, company, ID, etc. (>= 3 words).
                if (tokens.size < 3) {
                    stableConsecutivePasses = 0
                    lastTokens = null
                    dispatchState(CardDetectionState.NO_CARD, 0f)
                    return@addOnSuccessListener
                }

                // An ID card IS present! Now verify stability & stillness
                val prevTokens = lastTokens
                lastTokens = tokens

                // Check text consistency between consecutive frames
                val isTextConsistent = if (prevTokens != null) {
                    val intersection = tokens.intersect(prevTokens).size
                    val union = tokens.union(prevTokens).size
                    (intersection.toFloat() / union.toFloat()) >= 0.65f
                } else {
                    false
                }

                if (isStill && isTextConsistent) {
                    stableConsecutivePasses++
                } else if (!isStill) {
                    // Significant movement resets stability
                    stableConsecutivePasses = max(0, stableConsecutivePasses - 1)
                }

                val progress = (stableConsecutivePasses.toFloat() / targetStablePasses.toFloat()).coerceIn(0f, 1f)
                val state = when {
                    progress >= 1f -> CardDetectionState.CAPTURED
                    progress > 0f -> CardDetectionState.HOLDING_STEADY
                    else -> CardDetectionState.CARD_DETECTED
                }

                dispatchState(state, progress)

                // Trigger auto-capture when stability reaches 100%
                if (progress >= 1f) {
                    if (isCapturing.compareAndSet(false, true)) {
                        mainHandler.post {
                            onCaptured(bitmap, visionText)
                        }
                    }
                }
            }
            .addOnCompleteListener {
                isOcrRunning = false
            }
    }

    fun release() {
        recognizer.close()
    }
}
