package com.example.synclyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

/**
 * Burns the bilingual transcript into exported frames.
 *
 * Draws a frame-sized transparent bitmap (panel + text), so the overlay maps 1:1 onto the
 * video regardless of how Media3 scales overlays. The bitmap only changes when the visible
 * line or highlighted word changes, and the last one is cached.
 *
 * [presentationTimeUs] is the position on the source timeline, the same clock the preview
 * uses with `player.currentPosition` (no trimming/speed edits are applied by the exporter).
 */
@UnstableApi
class LyricsOverlay(
    private val segments: List<TranscriptSegment>,
    private val frameWidth: Int,
    private val frameHeight: Int
) : BitmapOverlay() {

    private data class Key(val segment: Int, val word: Int)

    private var cachedKey: Key? = null
    private var cachedBitmap: Bitmap? = null

    private val short = minOf(frameWidth, frameHeight).toFloat()
    private val originalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = short * 0.050f; typeface = Typeface.DEFAULT
    }
    private val translationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 220, 220, 220); textSize = short * 0.038f; typeface = Typeface.DEFAULT
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val posMs = presentationTimeUs / 1000
        val seg = displaySegmentIndex(segments, posMs)
        val word = if (seg >= 0) activeWordIndex(segments[seg].words, posMs) else -1
        val key = Key(seg, word)
        if (key != cachedKey) {
            // New bitmap each change: the previous one may still be queued for GL upload.
            cachedBitmap = render(seg, word)
            cachedKey = key
        }
        return cachedBitmap!!
    }

    private fun render(segIndex: Int, wordIndex: Int): Bitmap {
        val bmp = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        if (segIndex < 0) return bmp
        val seg = segments[segIndex]
        if (seg.words.isEmpty()) return bmp

        // Keep clear of notches / platform overlays: 6% side margins, 8% bottom margin.
        val sideMargin = frameWidth * 0.06f
        val textWidth = (frameWidth - 2 * sideMargin - short * 0.04f).toInt().coerceAtLeast(1)
        val pad = short * 0.025f

        val line = SpannableStringBuilder()
        seg.words.forEachIndexed { i, w ->
            if (i > 0) line.append(' ')
            val s = line.length
            line.append(w.text)
            if (i == wordIndex) {
                val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                line.setSpan(BackgroundColorSpan(0xFFFFD45C.toInt()), s, line.length, f)
                line.setSpan(ForegroundColorSpan(0xFF14151A.toInt()), s, line.length, f)
                line.setSpan(StyleSpan(Typeface.BOLD), s, line.length, f)
            }
        }
        val origLayout = layout(line, originalPaint, textWidth)
        val transLayout = seg.translation.takeIf { it.isNotBlank() }?.let { layout(it, translationPaint, textWidth) }

        val gap = if (transLayout != null) short * 0.015f else 0f
        val contentH = origLayout.height + gap + (transLayout?.height ?: 0)
        val panelH = contentH + 2 * pad
        val bottom = frameHeight * 0.92f
        val panel = RectF(sideMargin, (bottom - panelH).coerceAtLeast(0f), frameWidth - sideMargin, bottom)

        val c = Canvas(bmp)
        c.drawRoundRect(panel, pad, pad, panelPaint)
        c.save()
        c.translate(panel.left + (panel.width() - textWidth) / 2f, panel.top + pad)
        origLayout.draw(c)
        if (transLayout != null) {
            c.translate(0f, origLayout.height + gap)
            transLayout.draw(c)
        }
        c.restore()
        return bmp
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
}
