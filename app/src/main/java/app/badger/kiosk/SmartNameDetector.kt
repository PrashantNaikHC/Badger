package app.badger.kiosk

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent hybrid vision-geometry & NLP heuristic engine for ID cards.
 *
 * Runs fully on-device with zero extra bundle size and zero latency.
 *
 * Capabilities:
 * 1. Analyzes spatial layout, line bounding box heights (font prominence), and card coordinates.
 * 2. Filters out noise (alphanumeric IDs, dates, blood groups, departments, cities, generic labels).
 * 3. Accurately identifies the employee name tokens and pre-selects them.
 * 4. Auto-detects company/organization header to suggest a template name (e.g. "Google Badge").
 * 5. Calculates normalized ROI (relativeYMin, relativeYMax) and card orientation (isPortrait).
 */
object SmartNameDetector {

    data class CardWordToken(
        val id: Int,
        val text: String,
        val boundingBox: Rect,
        val lineIndex: Int = 0
    )

    data class DetectionResult(
        val suggestedTemplateName: String?,
        val detectedTokens: List<String>,
        val guessedNameIndices: Set<Int>,
        val extractedName: String?,
        val isPortrait: Boolean,
        val relativeYMin: Float?,
        val relativeYMax: Float?
    )

    private val LABEL_BLACKLIST = setOf(
        "NAME", "EMPLOYEE", "EMP", "ID", "IDENTITY", "CARD", "BADGE", "STAFF",
        "NO", "NUMBER", "CODE", "DESIG", "DESIGNATION", "DEPT", "DEPARTMENT",
        "BLOOD", "GROUP", "DOB", "DOJ", "VALID", "EXPIRY", "VALIDITY", "EXPIRES",
        "SIGNATURE", "HOLDER", "AUTHORIZED", "AUTHORISED", "ISSUED", "DATE",
        "ISSUE", "PERMIT", "VISITOR", "CONTRACTOR", "OFFICIAL", "PASS", "ACCESS",
        "GUEST", "SECURITY", "HEADQUARTERS", "LOCATION", "OFFICE", "CAMPUS"
    )

    private val COMPANY_SUFFIX_BLACKLIST = setOf(
        "INC", "LTD", "LIMITED", "CORP", "CORPORATION", "LLC", "PVT", "PRIVATE",
        "TECHNOLOGIES", "SOLUTIONS", "SYSTEMS", "GLOBAL", "SERVICES", "GROUP"
    )

    private val LOCATION_BLACKLIST = setOf(
        "BANGALORE", "BENGALURU", "MUMBAI", "DELHI", "HYDERABAD", "CHENNAI",
        "PUNE", "KOLKATA", "GURGAON", "NOIDA", "AHMEDABAD", "SAN FRANCISCO",
        "NEW YORK", "SEATTLE", "LONDON", "SINGAPORE", "DUBAI", "SYDNEY",
        "CALIFORNIA", "TEXAS", "INDIA", "USA", "UK"
    )

    private val BLOOD_GROUP_REGEX = Regex("""^(?i)(?:A|B|AB|O)[+-](?:VE)?$""")
    private val DATE_REGEX = Regex("""\b\d{1,4}[-/. ]\d{1,2}[-/. ]\d{1,4}\b""")
    private val DIGIT_REGEX = Regex(""".*\d+.*""")
    private val STRICT_NAME_WORD_REGEX = Regex("""^[\p{L}][\p{L}.'\-]*$""")

    data class TokenInfo(
        val text: String,
        val lineIndex: Int,
        val box: Rect,
        val lineHeight: Int,
        val globalIndex: Int
    )

    fun extractWordTokens(visionText: Text): List<CardWordToken> {
        val tokens = mutableListOf<CardWordToken>()
        var idCounter = 0

        val lines = visionText.textBlocks.flatMap { it.lines }
            .filter { it.text.isNotBlank() }
            .sortedWith(
                compareBy<Text.Line> { (it.boundingBox?.top ?: 0) / 15 }
                    .thenBy { it.boundingBox?.left ?: 0 }
            )

        lines.forEachIndexed { lineIdx, line ->
            val elements = line.elements
            if (elements.isNotEmpty()) {
                for (element in elements) {
                    val clean = element.text.trim().trim(',', ':', '|', '.', '-', ';', '/', '(', ')')
                    val box = element.boundingBox ?: line.boundingBox ?: continue
                    if (clean.isNotBlank()) {
                        tokens.add(
                            CardWordToken(
                                id = idCounter++,
                                text = clean,
                                boundingBox = box,
                                lineIndex = lineIdx
                            )
                        )
                    }
                }
            } else {
                val words = line.text.trim().split(Regex("\\s+"))
                val box = line.boundingBox ?: return@forEachIndexed
                val wordW = box.width() / max(1, words.size)
                for ((idx, w) in words.withIndex()) {
                    val clean = w.trim(',', ':', '|', '.', '-', ';', '/', '(', ')')
                    if (clean.isNotBlank()) {
                        val wordBox = Rect(
                            box.left + idx * wordW,
                            box.top,
                            box.left + (idx + 1) * wordW,
                            box.bottom
                        )
                        tokens.add(
                            CardWordToken(
                                id = idCounter++,
                                text = clean,
                                boundingBox = wordBox,
                                lineIndex = lineIdx
                            )
                        )
                    }
                }
            }
        }
        return tokens
    }

    fun suggestTemplateName(tokens: List<CardWordToken>, imageHeight: Int): String? {
        val topTokens = tokens.filter {
            it.boundingBox.top.toFloat() / max(1, imageHeight).toFloat() <= 0.22f
        }
        if (topTokens.isEmpty()) return null

        val companyTokens = topTokens.filter {
            val upper = it.text.uppercase(Locale.ROOT)
            !LABEL_BLACKLIST.contains(upper) &&
                !DIGIT_REGEX.matches(it.text) &&
                it.text.length >= 2
        }
        if (companyTokens.isEmpty()) return null

        val name = companyTokens.take(3).joinToString(" ") { t ->
            t.text.lowercase(Locale.ROOT).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
        return if (name.isNotBlank()) "$name Badge" else null
    }

    fun analyze(visionText: Text, frameWidth: Int, frameHeight: Int): DetectionResult {
        // Collect all lines with bounding boxes
        val allLines = visionText.textBlocks.flatMap { it.lines }
            .filter { it.text.isNotBlank() }

        if (allLines.isEmpty()) {
            return DetectionResult(
                suggestedTemplateName = null,
                detectedTokens = emptyList(),
                guessedNameIndices = emptySet(),
                extractedName = null,
                isPortrait = frameHeight >= frameWidth,
                relativeYMin = null,
                relativeYMax = null
            )
        }

        // Determine bounding box of all text to estimate card bounds
        var textMinX = Int.MAX_VALUE
        var textMinY = Int.MAX_VALUE
        var textMaxX = 0
        var textMaxY = 0

        for (line in allLines) {
            val b = line.boundingBox ?: continue
            textMinX = min(textMinX, b.left)
            textMinY = min(textMinY, b.top)
            textMaxX = max(textMaxX, b.right)
            textMaxY = max(textMaxY, b.bottom)
        }

        val cardBoundsHeight = max(1, textMaxY - textMinY)
        val cardBoundsWidth = max(1, textMaxX - textMinX)
        val isPortrait = cardBoundsHeight >= cardBoundsWidth

        // Group into lines sorted vertically
        val sortedLines = allLines.sortedWith(
            compareBy<Text.Line> { (it.boundingBox?.top ?: 0) / 15 }
                .thenBy { it.boundingBox?.left ?: 0 }
        )

        val tokenList = mutableListOf<TokenInfo>()
        var globalTokenIdx = 0

        sortedLines.forEachIndexed { lineIdx, line ->
            val lineBox = line.boundingBox ?: Rect(0, 0, 100, 30)
            val lineH = max(1, lineBox.height())
            val words = line.text.trim().split(Regex("\\s+"))

            for (w in words) {
                val cleanWord = w.trim(',', ':', '|', '.', '-', ';', '/', '(', ')')
                if (cleanWord.isNotBlank()) {
                    tokenList.add(
                        TokenInfo(
                            text = cleanWord,
                            lineIndex = lineIdx,
                            box = lineBox,
                            lineHeight = lineH,
                            globalIndex = globalTokenIdx++
                        )
                    )
                }
            }
        }

        val detectedTokens = tokenList.map { it.text }
        val maxLineHeight = tokenList.maxOfOrNull { it.lineHeight } ?: 1

        // 1. Suggest Template Name from top header text
        val suggestedTemplateName = detectTemplateName(tokenList, textMinY, cardBoundsHeight)

        // 2. Identify candidate name lines/tokens
        val tokensByLine = tokenList.groupBy { it.lineIndex }
        var bestCandidateTokens = listOf<TokenInfo>()
        var bestScore = -100f

        for ((_, lineTokens) in tokensByLine) {
            val score = scoreNameCandidateLine(
                lineTokens,
                maxLineHeight,
                textMinY,
                cardBoundsHeight
            )
            if (score > bestScore) {
                bestScore = score
                bestCandidateTokens = lineTokens
            }
        }

        // Filter the winning line to extract only name words
        val filteredNameTokens = bestCandidateTokens.filter { isValidNameWord(it.text) }

        val guessedIndices = if (bestScore > 0f && filteredNameTokens.isNotEmpty()) {
            filteredNameTokens.map { it.globalIndex }.toSet()
        } else {
            emptySet()
        }

        val extractedName = if (guessedIndices.isNotEmpty()) {
            filteredNameTokens.joinToString(" ") { t ->
                t.text.lowercase(Locale.ROOT).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
        } else null

        // Normalized relative Y bounds of the name region
        val relativeYMin = if (filteredNameTokens.isNotEmpty()) {
            val top = filteredNameTokens.minOf { it.box.top }
            ((top - textMinY).toFloat() / cardBoundsHeight).coerceIn(0f, 1f)
        } else null

        val relativeYMax = if (filteredNameTokens.isNotEmpty()) {
            val bottom = filteredNameTokens.maxOf { it.box.bottom }
            ((bottom - textMinY).toFloat() / cardBoundsHeight).coerceIn(0f, 1f)
        } else null

        return DetectionResult(
            suggestedTemplateName = suggestedTemplateName,
            detectedTokens = detectedTokens,
            guessedNameIndices = guessedIndices,
            extractedName = extractedName,
            isPortrait = isPortrait,
            relativeYMin = relativeYMin,
            relativeYMax = relativeYMax
        )
    }

    private fun scoreNameCandidateLine(
        tokens: List<TokenInfo>,
        maxLineHeight: Int,
        textMinY: Int,
        cardBoundsHeight: Int
    ): Float {
        if (tokens.isEmpty()) return -100f

        val fullText = tokens.joinToString(" ") { it.text }

        // Immediate disqualification rules:
        if (DIGIT_REGEX.matches(fullText)) return -100f
        if (DATE_REGEX.containsMatchIn(fullText)) return -100f
        if (tokens.any { BLOOD_GROUP_REGEX.matches(it.text) }) return -100f
        if (fullText.contains("@") || fullText.contains("http", ignoreCase = true) || fullText.contains(".com", ignoreCase = true)) return -100f

        val validNameTokens = tokens.filter { isValidNameWord(it.text) }
        if (validNameTokens.isEmpty()) return -100f

        val hasLabelKeyword = tokens.any { LABEL_BLACKLIST.contains(it.text.uppercase(Locale.ROOT)) }
        val hasLocationKeyword = tokens.any { LOCATION_BLACKLIST.contains(it.text.uppercase(Locale.ROOT)) }
        val hasCompanySuffix = tokens.any { COMPANY_SUFFIX_BLACKLIST.contains(it.text.uppercase(Locale.ROOT)) }

        var score = 0f

        // Font prominence
        val lineHeight = tokens.first().lineHeight
        val heightRatio = lineHeight.toFloat() / maxLineHeight.toFloat()
        score += heightRatio * 35f

        // Position on card
        val lineTop = tokens.minOf { it.box.top }
        val relativeY = (lineTop - textMinY).toFloat() / cardBoundsHeight.toFloat()

        if (relativeY in 0.18f..0.75f) {
            score += 25f
        } else if (relativeY < 0.18f) {
            score -= 25f
        } else {
            score -= 15f
        }

        when (validNameTokens.size) {
            2, 3 -> score += 30f
            1 -> score += 10f
            4 -> score += 15f
            else -> score -= 15f
        }

        if (hasLabelKeyword) score -= 40f
        if (hasLocationKeyword) score -= 30f
        if (hasCompanySuffix) score -= 35f

        val allCapitalizedOrUpper = validNameTokens.all {
            it.text == it.text.uppercase(Locale.ROOT) ||
                (it.text.isNotEmpty() && it.text[0].isUpperCase())
        }
        if (allCapitalizedOrUpper) score += 10f

        return score
    }

    fun isValidNameWord(word: String): Boolean {
        val clean = word.trim(',', ':', '|', '.', '-', ';')
        if (clean.length < 2) return false
        if (!STRICT_NAME_WORD_REGEX.matches(clean)) return false
        val upper = clean.uppercase(Locale.ROOT)
        if (LABEL_BLACKLIST.contains(upper)) return false
        if (LOCATION_BLACKLIST.contains(upper)) return false
        if (COMPANY_SUFFIX_BLACKLIST.contains(upper)) return false
        return true
    }

    private fun detectTemplateName(
        tokens: List<TokenInfo>,
        textMinY: Int,
        cardBoundsHeight: Int
    ): String? {
        val topTokens = tokens.filter {
            val relY = (it.box.top - textMinY).toFloat() / cardBoundsHeight.toFloat()
            relY <= 0.22f
        }

        if (topTokens.isEmpty()) return null

        val companyTokens = topTokens.filter {
            val upper = it.text.uppercase(Locale.ROOT)
            !LABEL_BLACKLIST.contains(upper) &&
                !DIGIT_REGEX.matches(it.text) &&
                it.text.length >= 2
        }

        if (companyTokens.isEmpty()) return null

        val name = companyTokens.take(3).joinToString(" ") { t ->
            t.text.lowercase(Locale.ROOT).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }

        return if (name.isNotBlank()) "$name Badge" else null
    }

    data class ScoredToken(
        val text: String,
        val lineIndex: Int,
        val isNameWord: Boolean
    )

    /**
     * Fallback name extraction from raw OCR text lines using smart heuristics.
     * Accurately groups multi-line and single-line employee name tokens in the central card area.
     */
    fun extractNameFallback(lines: List<String>): String? {
        if (lines.isEmpty()) return null

        val totalLines = lines.size
        val scoredTokens = mutableListOf<ScoredToken>()

        for ((lineIdx, line) in lines.withIndex()) {
            val words = line.trim().split(Regex("[\\s|:;,/]+"))
                .map { it.trim(',', ':', '|', '.', '-', ';') }
                .filter { it.isNotEmpty() }

            // When there are 3 or more lines, line 0 is the organization/card header
            val isHeaderLine = (lineIdx == 0 && totalLines >= 3)

            for (w in words) {
                scoredTokens.add(
                    ScoredToken(
                        text = w,
                        lineIndex = lineIdx,
                        isNameWord = isValidNameWord(w) && !DIGIT_REGEX.matches(w) && !isHeaderLine
                    )
                )
            }
        }

        if (scoredTokens.isEmpty()) return null

        // Collect continuous spans of valid name words (even across adjacent lines)
        val candidateSpans = mutableListOf<List<ScoredToken>>()
        var currentSpan = mutableListOf<ScoredToken>()

        for (st in scoredTokens) {
            if (st.isNameWord) {
                currentSpan.add(st)
            } else {
                if (currentSpan.isNotEmpty()) {
                    candidateSpans.add(currentSpan)
                    currentSpan = mutableListOf()
                }
            }
        }
        if (currentSpan.isNotEmpty()) {
            candidateSpans.add(currentSpan)
        }

        if (candidateSpans.isEmpty()) return null

        var bestSpan: List<ScoredToken>? = null
        var bestScore = -100f

        for (span in candidateSpans) {
            val words = if (span.size > 3) span.take(3) else span
            var score = 0f

            // 1. Length bonus
            when (words.size) {
                2, 3 -> score += 35f
                1 -> score += 10f
                else -> score += 5f
            }

            // 2. Position bonus (penalize line 0 if there are multiple lines, reward middle lines)
            val avgLine = words.map { it.lineIndex }.average().toFloat()
            val relPos = if (totalLines > 1) avgLine / (totalLines - 1).toFloat() else 0.5f

            if (relPos in 0.15f..0.75f) {
                score += 25f
            } else if (relPos < 0.15f && totalLines >= 3) {
                // Top line: likely company name header
                score -= 20f
            } else {
                score -= 10f
            }

            // 3. Casing bonus
            val allTitleOrUpper = words.all {
                it.text == it.text.uppercase(Locale.ROOT) || it.text[0].isUpperCase()
            }
            if (allTitleOrUpper) score += 15f

            if (score > bestScore) {
                bestScore = score
                bestSpan = words
            }
        }

        return bestSpan?.joinToString(" ") { w ->
            w.text.lowercase(Locale.ROOT).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }
    }
}
