package app.badger.kiosk

import kotlin.math.min

/**
 * Extracts an employee's name from OCR lines using configurable start (left/top)
 * and end (right/bottom) anchors.
 *
 * Anchors are typically static labels printed on distinct lines before or after the employee's name.
 * The name itself may span one or multiple lines.
 *
 * Enhancements:
 * 1. Tolerant / Fuzzy Anchor Matching: Handles minor OCR character errors (e.g. "G00GLE" vs "GOOGLE").
 * 2. Smart Heuristic Fallback: If anchors cannot be located (e.g. obscured by glare or angle),
 *    falls back to [SmartNameDetector] to extract the most prominent name candidate.
 */
object NameExtractor {
    private val wordRegex = Regex("""^\p{L}[\p{L}.'\-]+$""")

    /**
     * @param lines OCR text blocks/lines in reading order (top to bottom).
     * @param leftAnchor Start anchor (optional). Case-insensitive search.
     * @param rightAnchor End anchor (optional). Case-insensitive search.
     * @param allowFallback When true, falls back to SmartNameDetector heuristics if anchor extraction fails.
     */
    fun extract(
        lines: List<String>,
        leftAnchor: String?,
        rightAnchor: String?,
        allowFallback: Boolean = false
    ): String? {
        val cleanLeft = leftAnchor?.trim()?.takeIf { it.isNotEmpty() }
        val cleanRight = rightAnchor?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanLeft == null && cleanRight == null) {
            return if (allowFallback) SmartNameDetector.extractNameFallback(lines) else null
        }

        // Try anchor-based extraction
        val anchorResult = extractByAnchors(lines, cleanLeft, cleanRight)
        if (anchorResult != null) return anchorResult

        // If anchor extraction failed and fallback is allowed, try smart heuristics
        return if (allowFallback) SmartNameDetector.extractNameFallback(lines) else null
    }

    private fun extractByAnchors(
        lines: List<String>,
        cleanLeft: String?,
        cleanRight: String?
    ): String? {
        // Find left anchor
        val leftMatch = if (cleanLeft != null) findAnchorMatch(lines, cleanLeft, 0) else null
        if (cleanLeft != null && leftMatch == null) return null

        val leftLineIdx = leftMatch?.first ?: -1

        // Find right anchor (must be at or after left anchor line)
        val searchFrom = if (leftLineIdx >= 0) leftLineIdx else 0
        val rightMatch = if (cleanRight != null) findAnchorMatch(lines, cleanRight, searchFrom) else null
        if (cleanRight != null && rightMatch == null) return null

        val rightLineIdx = rightMatch?.first ?: lines.size

        if (leftLineIdx > rightLineIdx) return null
        if (leftLineIdx == rightLineIdx && (cleanLeft == null || cleanRight == null)) return null

        // Collect Candidate Words/Lines between the anchors
        val candidateWords = mutableListOf<String>()

        for (i in (if (leftLineIdx >= 0) leftLineIdx else 0)..rightLineIdx.coerceAtMost(lines.lastIndex)) {
            val line = lines[i]
            val effectiveText = when {
                i == leftLineIdx && i == rightLineIdx -> {
                    // Both anchors on the same line: slice between them
                    val leftRange = leftMatch?.second ?: continue
                    val subAfterLeft = line.substring(leftRange.last + 1)
                    val rightRange = rightMatch?.second ?: continue
                    val adjustedRightFirst = rightRange.first - (leftRange.last + 1)
                    if (adjustedRightFirst > 0 && adjustedRightFirst <= subAfterLeft.length) {
                        subAfterLeft.substring(0, adjustedRightFirst)
                    } else ""
                }
                i == leftLineIdx -> {
                    // Start after the left anchor on this line
                    val leftRange = leftMatch?.second
                    if (leftRange != null && leftRange.last + 1 <= line.length) {
                        line.substring(leftRange.last + 1)
                    } else ""
                }
                i == rightLineIdx -> {
                    // Stop before the right anchor on this line
                    val rightRange = rightMatch?.second
                    if (rightRange != null && rightRange.first <= line.length) {
                        line.substring(0, rightRange.first)
                    } else ""
                }
                else -> line
            }

            // Split into tokens
            val tokens = effectiveText.trim()
                .split(Regex("[\\s|:;,/]+"))
                .map { it.trim(',', ':', '|', '.', '-', ';') }
                .filter { it.isNotEmpty() && wordRegex.matches(it) }

            candidateWords.addAll(tokens)
        }

        if (candidateWords.isEmpty()) return null

        val name = candidateWords.joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.titlecase() }
        }

        return name.ifBlank { null }
    }

    /**
     * Finds an anchor in lines starting from [startFromLine], using exact regex first,
     * then falling back to fuzzy token matching (Levenshtein distance <= 1 for words >= 4 chars).
     *
     * Returns Pair(lineIndex, matchCharacterRange) or null.
     */
    private fun findAnchorMatch(
        lines: List<String>,
        anchor: String,
        startFromLine: Int
    ): Pair<Int, IntRange>? {
        val anchorRegex = Regex("(?i)\\b${Regex.escape(anchor)}\\b")

        for (i in startFromLine until lines.size) {
            val line = lines[i]
            val exact = anchorRegex.find(line)
            if (exact != null) {
                return Pair(i, exact.range)
            }
        }

        // Fuzzy fallback for OCR variations (e.g. "G00GLE" vs "GOOGLE")
        if (anchor.length >= 3) {
            val normAnchor = normalizeOcr(anchor)
            val maxDistance = if (anchor.length >= 6) 2 else 1

            for (i in startFromLine until lines.size) {
                val line = lines[i]
                val tokens = line.split(Regex("\\s+"))
                var currentPos = 0
                for (token in tokens) {
                    val tokenIdx = line.indexOf(token, currentPos)
                    if (tokenIdx < 0) continue
                    currentPos = tokenIdx + token.length
                    val cleanToken = token.trim(',', ':', '|', '.', '-', ';')
                    val normToken = normalizeOcr(cleanToken)
                    if (normToken == normAnchor || levenshtein(normToken, normAnchor) <= maxDistance) {
                        return Pair(i, tokenIdx until (tokenIdx + token.length))
                    }
                }
            }
        }

        return null
    }

    private fun normalizeOcr(s: String): String {
        return s.uppercase()
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('8', 'B')
            .replace('5', 'S')
    }

    private fun levenshtein(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
