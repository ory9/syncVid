package com.example.synclyrics

/** Confidence below this is flagged for human review (see docs/ALIGNMENT.md). */
const val LOW_CONFIDENCE = 0.6f
const val MIN_WORD_MS = 60L
const val MAX_WORD_MS = 8_000L

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float? = null
) {
    init {
        require(startMs >= 0) { "startMs must be >= 0 (was $startMs for \"$text\")" }
        // docs/ALIGNMENT.md: endMs > startMs (zero-length words can never be active in [start, end)).
        require(endMs > startMs) { "endMs must be > startMs (was $startMs..$endMs for \"$text\")" }
    }

    val isLowConfidence: Boolean get() = confidence != null && confidence < LOW_CONFIDENCE
}

data class TranscriptSegment(
    val id: String,
    val original: String,
    val translation: String,
    val words: List<TimedWord>
) {
    // Computed (not constructor defaults) so they stay correct after copy(words = ...).
    val startMs: Long get() = words.minOfOrNull { it.startMs } ?: 0L
    val endMs: Long get() = words.maxOfOrNull { it.endMs } ?: startMs
}

/** Word intervals are [startMs, endMs). Returns -1 during pauses or outside the transcript. */
fun activeWordIndex(words: List<TimedWord>, positionMs: Long): Int =
    words.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }

/**
 * Segment to keep in view: the last segment that has started at [positionMs]
 * (stays on the previous line during pauses). -1 before the first segment.
 */
fun currentSegmentIndex(segments: List<TranscriptSegment>, positionMs: Long): Int =
    segments.indexOfLast { it.words.isNotEmpty() && it.startMs <= positionMs }

/**
 * Segment to show on screen / burn into export: like [currentSegmentIndex], but the line
 * disappears [lingerMs] after its last word ends (so it doesn't hang over long instrumentals).
 */
fun displaySegmentIndex(
    segments: List<TranscriptSegment>,
    positionMs: Long,
    lingerMs: Long = 1_500L
): Int {
    val i = currentSegmentIndex(segments, positionMs)
    return if (i >= 0 && positionMs < segments[i].endMs + lingerMs) i else -1
}

enum class IssueKind { EMPTY_TEXT, UNSORTED, OVERLAP, TOO_SHORT, TOO_LONG, PAST_END, LOW_CONFIDENCE, EMPTY_SEGMENT }

data class TimingIssue(
    val segmentId: String,
    val wordIndex: Int?,
    val kind: IssueKind,
    val message: String
)

/**
 * Non-fatal review checks. Hard invariants (start >= 0, end > start) are enforced by [TimedWord];
 * gaps are intentionally NOT reported (pauses are normal).
 */
fun validateTranscript(segments: List<TranscriptSegment>, durationMs: Long? = null): List<TimingIssue> {
    val issues = mutableListOf<TimingIssue>()
    var prevSegEnd = Long.MIN_VALUE
    var prevSegId: String? = null
    for (seg in segments) {
        if (seg.words.isEmpty()) {
            issues += TimingIssue(seg.id, null, IssueKind.EMPTY_SEGMENT, "Segment has no timed words")
            continue
        }
        if (prevSegId != null && seg.startMs < prevSegEnd) {
            issues += TimingIssue(seg.id, null, IssueKind.OVERLAP, "Starts before $prevSegId ends")
        }
        seg.words.forEachIndexed { i, w ->
            if (w.text.isBlank()) issues += TimingIssue(seg.id, i, IssueKind.EMPTY_TEXT, "Blank word")
            val d = w.endMs - w.startMs
            if (d < MIN_WORD_MS) issues += TimingIssue(seg.id, i, IssueKind.TOO_SHORT, "\"${w.text}\" lasts ${d}ms")
            if (d > MAX_WORD_MS) issues += TimingIssue(seg.id, i, IssueKind.TOO_LONG, "\"${w.text}\" lasts ${d}ms")
            if (w.isLowConfidence) issues += TimingIssue(seg.id, i, IssueKind.LOW_CONFIDENCE, "\"${w.text}\" confidence ${w.confidence}")
            if (durationMs != null && w.endMs > durationMs) {
                issues += TimingIssue(seg.id, i, IssueKind.PAST_END, "\"${w.text}\" ends after media duration ${durationMs}ms")
            }
            if (i > 0) {
                val p = seg.words[i - 1]
                if (w.startMs < p.startMs) issues += TimingIssue(seg.id, i, IssueKind.UNSORTED, "\"${w.text}\" starts before previous word")
                else if (w.startMs < p.endMs) issues += TimingIssue(seg.id, i, IssueKind.OVERLAP, "\"${w.text}\" overlaps \"${p.text}\"")
            }
        }
        prevSegEnd = seg.endMs
        prevSegId = seg.id
    }
    return issues
}

/** Applies `audioOffsetMs` (docs/ALIGNMENT.md step 4). Words ending at/before 0 are dropped; starts clamp to 0. */
fun applyOffset(segments: List<TranscriptSegment>, offsetMs: Long): List<TranscriptSegment> {
    if (offsetMs == 0L) return segments
    return segments.map { seg ->
        seg.copy(words = seg.words.mapNotNull { w ->
            val e = w.endMs + offsetMs
            if (e <= 0) null else w.copy(startMs = (w.startMs + offsetMs).coerceAtLeast(0L), endMs = e)
        })
    }
}

fun sampleTranscript() = listOf(
    TranscriptSegment(
        id = "sample-1",
        original = "Mukama obeéranga nange nomponya",
        translation = "Lord, you have always been with me",
        words = listOf(
            TimedWord("Mukama", 0, 700),
            TimedWord("obeéranga", 700, 1450),
            TimedWord("nange", 1450, 1900),
            TimedWord("nomponya", 1900, 2700)
        )
    ),
    TranscriptSegment(
        id = "sample-2",
        original = "Ejoogo ly'omubi",
        translation = "The yoke of evil",
        words = listOf(
            TimedWord("Ejoogo", 3000, 3650),
            TimedWord("ly'omubi", 3650, 4500)
        )
    )
)
