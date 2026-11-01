package app.badger.kiosk
/**
 * Extracts an employee's name from OCR lines using configurable start (left/top)
 * and end (right/bottom) anchors.
 *
 * Anchors are typically static labels printed on distinct lines before or after the employee's name.
 * The name itself may span one or multiple lines.
 *
 * Rules:
 * - If [leftAnchor] is specified, extraction only considers content AFTER that anchor.
 * - If [rightAnchor] is specified, extraction only considers content BEFORE that anchor.
 * - If neither anchor is specified, extraction returns null.
 * - Extracts lines located between anchors, strips trailing punctuation, and normalizes capitalization.
 */
object NameExtractor {
    private val wordRegex = Regex("""^\p{L}[\p{L}.'\-]+$""")

    /**
     * @param lines OCR text blocks/lines in reading order (top to bottom).
     * @param leftAnchor Start anchor (optional). Case-insensitive search.
     * @param rightAnchor End anchor (optional). Case-insensitive search.
     */
    fun extract(
        lines: List<String>,
        leftAnchor: String?,
        rightAnchor: String?
    ): String? {
        val cleanLeft = leftAnchor?.trim()?.takeIf { it.isNotEmpty() }
        val cleanRight = rightAnchor?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanLeft == null && cleanRight == null) return null

        val leftRegex = cleanLeft?.let { Regex("(?i)\\b${Regex.escape(it)}\\b") }
        val rightRegex = cleanRight?.let { Regex("(?i)\\b${Regex.escape(it)}\\b") }

        // Find the index of the line containing the left anchor (or -1 if none)
        var leftLineIdx = -1
        if (leftRegex != null) {
            leftLineIdx = lines.indexOfFirst { leftRegex.containsMatchIn(it) }
            if (leftLineIdx == -1) return null // Left anchor required but not found
        }

        // Find the index of the line containing the right anchor
        var rightLineIdx = lines.size
        if (rightRegex != null) {
            // Must appear after the left anchor line
            val searchFrom = if (leftLineIdx >= 0) leftLineIdx else 0
            val foundRel = lines.drop(searchFrom).indexOfFirst { rightRegex.containsMatchIn(it) }
            if (foundRel == -1) return null // Right anchor required but not found
            rightLineIdx = searchFrom + foundRel
        }

        if (leftLineIdx > rightLineIdx) return null
        if (leftLineIdx == rightLineIdx && (leftRegex == null || rightRegex == null)) return null

        // Collect Candidate Words/Lines between the anchors
        val candidateWords = mutableListOf<String>()

        for (i in (if (leftLineIdx >= 0) leftLineIdx else 0)..rightLineIdx.coerceAtMost(lines.lastIndex)) {
            val line = lines[i]
            val effectiveText = when {
                i == leftLineIdx && i == rightLineIdx -> {
                    // Both anchors on the same line: slice between them
                    val leftMatch = leftRegex!!.find(line) ?: continue
                    val subAfterLeft = line.substring(leftMatch.range.last + 1)
                    val rightMatch = rightRegex!!.find(subAfterLeft) ?: continue
                    subAfterLeft.substring(0, rightMatch.range.first)
                }
                i == leftLineIdx -> {
                    // Start after the left anchor on this line
                    val m = leftRegex!!.find(line)
                    if (m != null) line.substring(m.range.last + 1) else ""
                }
                i == rightLineIdx -> {
                    // Stop before the right anchor on this line
                    val m = rightRegex!!.find(line)
                    if (m != null) line.substring(0, m.range.first) else ""
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
}
