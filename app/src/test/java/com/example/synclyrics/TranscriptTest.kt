package com.example.synclyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptTest {
    private val words = listOf(
        TimedWord("hello", 100, 300),
        TimedWord("world", 300, 600)
    )

    @Test fun findsWordAtStart() = assertEquals(0, activeWordIndex(words, 100))
    @Test fun endIsExclusive() = assertEquals(1, activeWordIndex(words, 300))
    @Test fun lastWordEndIsExclusive() = assertEquals(-1, activeWordIndex(words, 600))
    @Test fun beforeFirstWordHasNoActiveWord() = assertEquals(-1, activeWordIndex(words, 99))
    @Test fun pauseBetweenWordsHasNoActiveWord() = assertEquals(-1, activeWordIndex(words, 700))

    @Test fun seekingBackwardAndForwardIsStateless() {
        assertEquals(1, activeWordIndex(words, 450))
        assertEquals(0, activeWordIndex(words, 150))
        assertEquals(1, activeWordIndex(words, 599))
    }

    @Test fun rejectsZeroLengthAndNegativeWords() {
        assertThrows(IllegalArgumentException::class.java) { TimedWord("x", 10, 10) }
        assertThrows(IllegalArgumentException::class.java) { TimedWord("x", 10, 5) }
        assertThrows(IllegalArgumentException::class.java) { TimedWord("x", -1, 5) }
    }

    @Test fun segmentBoundsFollowCopy() {
        val s = TranscriptSegment("a", "hello world", "", words)
        assertEquals(100, s.startMs); assertEquals(600, s.endMs)
        val t = s.copy(words = listOf(TimedWord("z", 1000, 1500)))
        assertEquals(1000, t.startMs); assertEquals(1500, t.endMs)
    }

    private val segs = sampleTranscript() // 0..2700 and 3000..4500

    @Test fun currentSegmentHoldsDuringPause() {
        assertEquals(0, currentSegmentIndex(segs, 2800))
        assertEquals(1, currentSegmentIndex(segs, 3000))
        assertEquals(1, currentSegmentIndex(segs, 99_999))
    }

    @Test fun currentSegmentBeforeStartIsNone() {
        val late = listOf(TranscriptSegment("a", "", "", listOf(TimedWord("w", 500, 900))))
        assertEquals(-1, currentSegmentIndex(late, 100))
    }

    @Test fun displaySegmentLingersThenHides() {
        assertEquals(1, displaySegmentIndex(segs, 4500 + 1_499))
        assertEquals(-1, displaySegmentIndex(segs, 4500 + 1_500))
        assertEquals(0, displaySegmentIndex(segs, 0))
    }

    @Test fun validationFlagsOverlapPastEndLowConfidenceAndShortWords() {
        val seg = TranscriptSegment(
            "s", "", "", listOf(
                TimedWord("a", 0, 500),
                TimedWord("b", 400, 450, confidence = 0.2f),
                TimedWord("c", 450, 9_000)
            )
        )
        val kinds = validateTranscript(listOf(seg), durationMs = 5_000).map { it.kind }.toSet()
        assertTrue(IssueKind.OVERLAP in kinds)
        assertTrue(IssueKind.TOO_SHORT in kinds)
        assertTrue(IssueKind.LOW_CONFIDENCE in kinds)
        assertTrue(IssueKind.TOO_LONG in kinds)
        assertTrue(IssueKind.PAST_END in kinds)
    }

    @Test fun gapsAreNotIssuesAndSampleIsClean() {
        assertEquals(emptyList<TimingIssue>(), validateTranscript(sampleTranscript(), 10_000))
    }

    @Test fun offsetShiftsAndClamps() {
        val shifted = applyOffset(listOf(TranscriptSegment("a", "", "", words)), -200)
        assertEquals(listOf(0L to 100L, 100L to 400L), shifted[0].words.map { it.startMs to it.endMs })
        val dropped = applyOffset(listOf(TranscriptSegment("a", "", "", words)), -300)
        assertEquals(listOf("world"), dropped[0].words.map { it.text })
    }
}
