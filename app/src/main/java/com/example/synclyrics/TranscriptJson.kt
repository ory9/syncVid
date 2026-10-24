package com.example.synclyrics

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class TranscriptParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parses the docs/API_CONTRACT.md job result (or just its `segments` array).
 * Optional root field `audioOffsetMs` is applied to every word.
 */
fun parseTranscriptJson(text: String): List<TranscriptSegment> {
    try {
        val trimmed = text.trim().removePrefix("﻿")
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val arr = when (root) {
            is JSONArray -> root
            else -> (root as JSONObject).optJSONArray("segments")
                ?: throw TranscriptParseException("Missing \"segments\" array")
        }
        val offset = (root as? JSONObject)?.optLong("audioOffsetMs", 0L) ?: 0L
        val segments = (0 until arr.length()).map { i -> parseSegment(arr.getJSONObject(i), i) }
        if (segments.isEmpty()) throw TranscriptParseException("Transcript has no segments")
        return applyOffset(segments, offset)
    } catch (e: TranscriptParseException) {
        throw e
    } catch (e: JSONException) {
        throw TranscriptParseException("Invalid JSON: ${e.message}", e)
    } catch (e: IllegalArgumentException) { // TimedWord invariants
        throw TranscriptParseException(e.message ?: "Invalid word timing", e)
    }
}

private fun parseSegment(o: JSONObject, index: Int): TranscriptSegment {
    val id = o.optString("id", "").ifBlank { "segment-${index + 1}" }
    val wordsJson = o.optJSONArray("words") ?: JSONArray()
    val words = (0 until wordsJson.length()).map { j ->
        val w = wordsJson.getJSONObject(j)
        val confidence = if (w.has("confidence") && !w.isNull("confidence")) w.getDouble("confidence").toFloat() else null
        try {
            TimedWord(w.getString("text"), w.getLong("startMs"), w.getLong("endMs"), confidence)
        } catch (e: IllegalArgumentException) {
            throw TranscriptParseException("$id word ${j + 1}: ${e.message}", e)
        }
    }
    val original = o.optString("original", "").ifBlank { words.joinToString(" ") { it.text } }
    return TranscriptSegment(id, original, o.optString("translation", ""), words)
}

fun transcriptToJson(segments: List<TranscriptSegment>): String {
    val arr = JSONArray()
    segments.forEach { s ->
        arr.put(JSONObject().apply {
            put("id", s.id)
            put("original", s.original)
            put("translation", s.translation)
            put("words", JSONArray().also { wa ->
                s.words.forEach { w ->
                    wa.put(JSONObject().apply {
                        put("text", w.text); put("startMs", w.startMs); put("endMs", w.endMs)
                        w.confidence?.let { put("confidence", it.toDouble()) }
                    })
                }
            })
        })
    }
    return JSONObject().put("segments", arr).toString(2)
}
