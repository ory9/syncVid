package com.example.synclyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptJsonTest {
    private val contract = """
    {"jobId":"job_123","status":"completed","audioOffsetMs":100,"segments":[
      {"id":"segment-1","original":"Mukama obeéranga nange","translation":"Lord, you have always been with me",
       "words":[
        {"text":"Mukama","startMs":0,"endMs":700,"confidence":0.91},
        {"text":"obeéranga","startMs":700,"endMs":1450,"confidence":0.72},
        {"text":"nange","startMs":1450,"endMs":1900}]}]}
    """

    @Test fun parsesApiContractAndAppliesOffset() {
        val s = parseTranscriptJson(contract)
        assertEquals(1, s.size)
        assertEquals("Mukama obeéranga nange", s[0].original)
        assertEquals(100L, s[0].words[0].startMs)
        assertEquals(2000L, s[0].words[2].endMs)
        assertEquals(0.72f, s[0].words[1].confidence!!, 1e-6f)
        assertNull(s[0].words[2].confidence)
    }

    @Test fun acceptsBareArrayAndDefaultsIdAndOriginal() {
        val s = parseTranscriptJson("""[{"words":[{"text":"a","startMs":0,"endMs":10},{"text":"b","startMs":10,"endMs":20}]}]""")
        assertEquals("segment-1", s[0].id)
        assertEquals("a b", s[0].original)
    }

    @Test fun badInputsThrowParseException() {
        assertThrows(TranscriptParseException::class.java) { parseTranscriptJson("not json") }
        assertThrows(TranscriptParseException::class.java) { parseTranscriptJson("{}") }
        assertThrows(TranscriptParseException::class.java) { parseTranscriptJson("""{"segments":[]}""") }
        assertThrows(TranscriptParseException::class.java) {
            parseTranscriptJson("""{"segments":[{"words":[{"text":"a","startMs":5,"endMs":5}]}]}""")
        }
    }

    @Test fun roundTrips() {
        val a = sampleTranscript()
        assertEquals(a, parseTranscriptJson(transcriptToJson(a)))
    }
}
