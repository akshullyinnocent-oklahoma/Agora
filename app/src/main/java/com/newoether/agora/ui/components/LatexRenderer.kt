package com.newoether.agora.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import androidx.compose.ui.text.PlaceholderVerticalAlign
import ru.noties.jlatexmath.JLatexMathDrawable

data class LatexSpan(
    val isLatex: Boolean,
    val content: String,
    val display: Boolean = false,
)

// ── Patterns ──────────────────────────────────────────────────────────

/** $ followed by digits, not followed by $ or word char — bare dollar amounts. */
private val dollarAmountPattern = Regex("""\$\d+(?!\$)(?!\w)""")

/** Greek letters (U+0370–U+03FF) and math symbols (U+2200–U+2AFF). */
private val MATH_UNICODE = Regex("""[Ͱ-Ͽ∀-⫿]""")

/** LaTeX command — backslash + letters, e.g. \frac \alpha \text \sum. */
private val LATEX_COMMAND = Regex("""\\[a-zA-Z]+""")

/** Escaped dollar sign — strong LaTeX signal (not used in prose). */
private val ESCAPED_DOLLAR = Regex("""\\\$""")

/** Math structural markers — sub/superscript braces, begin/end. */
private val MATH_STRUCTURE = Regex("""[\^_]\{|\\begin|\\end""")

/** Pure number or percentage explicitly wrapped in $...$ */
private val PURE_NUM = Regex("""^-?\d+(\.\d+)?\\?%?$""")

/** Math operators — presence strongly suggests LaTeX, not prose. */
private val MATH_OPS = Regex("""[+\-*/=<>|^_]""")

/** English prose stop-words — signals natural language, not math. */
private val PROSE_WORD = Regex(
    """\b(and|or|the|a|an|in|on|to|for|of|is|are|was|were|be|it|we|you|he|she|they|""" +
    """costs?|price|dollar|another|other|this|that|each|per|total|only|about|""" +
    """more|less|than|with|from|by|can|will|would|should|could|may|not|but|""" +
    """one|two|three|four|five|ten|new|old|some|all|any|just|also|has|have|had|what)\b""",
    RegexOption.IGNORE_CASE
)

/** Sentence-ending punctuation followed by space — strong prose signal. */
private val SENTENCE_PUNCT = Regex("""[.!?;:]["») \t]""")

// ── Unicode gate ──────────────────────────────────────────────────────

/**
 * Returns true if every non-ASCII character in [s] is safely inside `{…}`,
 * OR is a Greek / math-symbol Unicode codepoint.
 * This lets `x_{中文}` and `\alpha` through while rejecting bare prose.
 */
private fun nonAsciiInsideBraces(s: String): Boolean {
    var depth = 0
    var esc = false
    for (ch in s) {
        if (esc) { esc = false; continue }
        if (ch == '\\') { esc = true; continue }
        if (ch == '{') { depth++; continue }
        if (ch == '}' && depth > 0) { depth--; continue }
        if (ch.code > 127 && depth == 0 && !MATH_UNICODE.matches(ch.toString()) && ch.isLetter()) {
            return false
        }
    }
    return true
}

// ── Content discriminator for $...$ ────────────────────────────────────

/**
 * Heuristic: given the trimmed content between a pair of `$` delimiters,
 * decide whether it is more likely LaTeX or natural-language prose.
 */
private fun isLikelyLatex(content: String): Boolean {
    val t = content.trim()
    if (t.isEmpty()) return false

    // Non-ASCII outside braces → prose (check BEFORE positive signals —
    // a rogue Chinese char must veto even if \mathrm or = is present)
    if (!nonAsciiInsideBraces(t)) return false

    // Strong positive signals — these are almost certainly LaTeX
    if (LATEX_COMMAND.containsMatchIn(t)) return true
    if (ESCAPED_DOLLAR.containsMatchIn(t)) return true
    if (MATH_STRUCTURE.containsMatchIn(t)) return true
    if (PURE_NUM.matches(t)) return true
    // Single character — math variable (not the article "a" / "A")
    if (t.length == 1 && t[0].isLetterOrDigit()) return true
    val hasMathOps = MATH_OPS.containsMatchIn(t)

    // Strong negative signals — these are almost certainly prose
    if (SENTENCE_PUNCT.containsMatchIn(t)) return false

    // Prose stop-words: fatal unless math operators are also present.
    // Single-letter matches are excluded — they are math variables.
    val words = t.split(Regex("""\s+"""))
    val hasProseWord = words.any { w -> w.length > 1 && PROSE_WORD.matches(w) }
    if (hasProseWord && !hasMathOps) return false

    // Many whitespace-delimited tokens → prose (unless math ops dominate)
    if (words.size > 5 && !hasMathOps) return false

    // No letters at all, no math ops, not pure number → not LaTeX
    // (catches "800–" where – is non-ASCII punctuation)
    if (t.none { it.isLetter() } && !hasMathOps) return false

    // Short, no prose signals — default to LaTeX
    return true
}

// ── Markdown escape ────────────────────────────────────────────────────

fun String.escapeDollarForMarkdown(): String = buildString {
    val src = this@escapeDollarForMarkdown
    var i = 0
    while (i < src.length) {
        val ch = src[i]
        val remaining = src.substring(i)

        // ``` fenced code block — pass through
        if (remaining.startsWith("```")) {
            val end = remaining.indexOf("```", 3)
            if (end >= 0) {
                append(remaining.substring(0, end + 3))
                i += end + 3; continue
            }
        }
        // ` inline code — pass through
        if (ch == '`') {
            val end = remaining.indexOf('`', 1)
            if (end >= 0) {
                append(remaining.substring(0, end + 1))
                i += end + 1; continue
            }
        }
        // Bare $ → \$ (but not already-escaped \$)
        if (ch == '$' && (i == 0 || src[i - 1] != '\\')) {
            append('\\')
        }
        append(ch)
        i++
    }
}

// ── Span parser ────────────────────────────────────────────────────────

fun parseLatexSpans(text: String): List<LatexSpan> {
    val spans = mutableListOf<LatexSpan>()
    val buf = StringBuilder()
    var i = 0
    while (i < text.length) {
        val remaining = text.substring(i)

        // ``` fenced code block — skip until closing ```
        if (remaining.startsWith("```")) {
            val end = remaining.indexOf("```", 3)
            if (end >= 0) {
                if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                spans.add(LatexSpan(false, remaining.substring(0, end + 3)))
                i += end + 3
                continue
            }
        }

        // ` inline code — skip until closing `
        if (remaining[0] == '`' && !remaining.startsWith("```")) {
            val end = remaining.indexOf('`', 1)
            if (end >= 0) {
                buf.append(remaining.substring(0, end + 1))
                i += end + 1
                continue
            }
        }

        // $$ display math
        if (remaining.startsWith("$$")) {
            val end = remaining.indexOf("$$", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank() && nonAsciiInsideBraces(latex)) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, true))
                } else if (latex.isNotBlank()) {
                    buf.append(remaining.substring(0, end + 2))
                }
                i += end + 2
                continue
            }
        }

        // \[ display math
        if (remaining.startsWith("\\[")) {
            val end = remaining.indexOf("\\]", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank() && nonAsciiInsideBraces(latex)) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, true))
                } else if (latex.isNotBlank()) {
                    buf.append(remaining.substring(0, end + 2))
                }
                i += end + 2
                continue
            }
        }

        // \( inline math
        if (remaining.startsWith("\\(")) {
            val end = remaining.indexOf("\\)", 2)
            if (end >= 0) {
                val latex = remaining.substring(2, end).trim()
                if (latex.isNotBlank() && nonAsciiInsideBraces(latex)) {
                    if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                    spans.add(LatexSpan(true, latex, false))
                } else if (latex.isNotBlank()) {
                    buf.append(remaining.substring(0, end + 2))
                }
                i += end + 2
                continue
            }
        }

        // $ inline math — skip if preceded by \ (escaped)
        if (remaining[0] == '$' && !remaining.startsWith("$$")) {
            val prevChar = if (i > 0) text[i - 1] else ' '
            if (prevChar != '\\') {
                // Find real closing $ on the same line (skip escaped \$)
                val lineEnd = remaining.indexOf('\n').let { if (it < 0) remaining.length else it }
                var end = remaining.indexOf('$', 1)
                while (end in 1..<lineEnd && remaining[end - 1] == '\\') {
                    end = remaining.indexOf('$', end + 1)
                }
                if (end in 1..<lineEnd) {
                    val latex = remaining.substring(1, end).trim()
                    if (latex.isNotEmpty() && isLikelyLatex(latex)) {
                        if (buf.isNotEmpty()) { spans.add(LatexSpan(false, buf.toString())); buf.clear() }
                        spans.add(LatexSpan(true, latex, false))
                        i += end + 1; continue
                    }
                    // Content looks like prose → only treat the opening $ as literal.
                    // Don't consume the closing $ — it may belong to a valid LaTeX pair later.
                    buf.append('$')
                    i++
                    continue
                }

                // No real same-line closing $ → treat $ as literal (could be a dollar amount)
                buf.append('$')
                i++
                continue
            }
        }

        // Escaped \$ → literal $
        if (remaining.startsWith("\\$")) {
            buf.append('$')
            i += 2
            continue
        }

        buf.append(remaining[0])
        i++
    }
    if (buf.isNotEmpty()) spans.add(LatexSpan(false, buf.toString()))

    // Escape $ in non-LaTeX spans for markdown compatibility
    for (idx in spans.indices) {
        val span = spans[idx]
        if (!span.isLatex) {
            spans[idx] = span.copy(content = span.content.escapeDollarForMarkdown())
        }
    }

    // Trim whitespace around inline LaTeX spans
    for (idx in spans.indices) {
        val span = spans[idx]
        if (span.isLatex && !span.display) {
            if (idx > 0) {
                val prev = spans[idx - 1]
                if (!prev.isLatex) spans[idx - 1] = prev.copy(content = prev.content.trimEnd())
            }
            if (idx + 1 < spans.size) {
                val next = spans[idx + 1]
                if (!next.isLatex) spans[idx + 1] = next.copy(content = next.content.trimStart())
            }
        }
    }

    return spans
}

// ── Rendering ──────────────────────────────────────────────────────────

fun renderLatexToBitmap(
    latex: String,
    textSize: Float = 48f,
    color: Int = 0xFF000000.toInt(),
    fallbackW: Int = 800,
    fallbackH: Int = 200,
    minW: Int = 0,
): Bitmap? {
    return try {
        val drawable = JLatexMathDrawable.builder(latex)
            .textSize(textSize)
            .color(color)
            .build()
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        val w = maxOf(iw.takeIf { it > 0 } ?: fallbackW, minW)
        val h = ih.takeIf { it > 0 } ?: fallbackH
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }
}

fun canRenderLatex(latex: String): Boolean {
    return try {
        JLatexMathDrawable.builder(latex).textSize(48f).color(0).build()
        true
    } catch (_: Exception) { false }
}

private fun encodeLatexUrl(latex: String): String {
    return Base64.encodeToString(latex.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
}

private fun decodeLatexUrl(encoded: String): String {
    return Base64.decode(encoded, Base64.URL_SAFE).decodeToString()
}

fun inlineLatexToMarkdown(latexContent: String): String {
    return "![latex](latex://${encodeLatexUrl(latexContent)})"
}

private fun renderTextToBitmap(text: String, textSize: Float, color: Int): Bitmap {
    val paint = android.graphics.Paint().apply {
        this.textSize = textSize * 0.6f
        this.color = color
        isAntiAlias = true
    }
    val fm = paint.fontMetrics
    val w = (paint.measureText(text) + 8f).toInt().coerceAtLeast(1)
    val h = ((fm.descent - fm.ascent) + 8f).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(0x00000000)
    val canvas = Canvas(bmp)
    canvas.drawText(text, 4f, -fm.ascent + 4f, paint)
    return bmp
}

// ── Image transformer ──────────────────────────────────────────────────

class LatexImageTransformer(
    private val textSize: Float = 40f,
    private val color: Int = 0xFF000000.toInt(),
) : ImageTransformer {
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 64
    }

    @Composable
    override fun transform(link: String): ImageData? {
        if (!link.startsWith("latex://")) return null
        val latex = try {
            decodeLatexUrl(link.removePrefix("latex://"))
        } catch (_: Exception) {
            return null
        }

        val bmp: Bitmap = synchronized(cache) {
            cache.getOrPut("$latex|$textSize|$color") {
                val fw = (textSize * 10).toInt()
                val fh = (textSize * 2).toInt()
                val rendered = renderLatexToBitmap(latex, textSize, color, fallbackW = fw, fallbackH = fh, minW = 0)
                if (rendered != null) {
                    rendered
                } else {
                    // Show original LaTeX source instead of a generic placeholder
                    renderTextToBitmap("$${latex}$", textSize, color)
                }
            }
        }
        return ImageData(
            painter = BitmapPainter(bmp.asImageBitmap()),
            contentDescription = latex,
            modifier = Modifier,
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.Fit,
        )
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        if (!link.startsWith("latex://")) return super.placeholderConfig(
            link, density, containerSize, imageWidth, imageSize, imageSizeChanged
        )
        val w = with(density) {
            if (imageSize.isUnspecified) (textSize * 2f).toDp().value
            else imageSize.width.toDp().value
        }
        val h = with(density) {
            if (imageSize.isUnspecified) (textSize * 1.2f).toDp().value
            else imageSize.height.toDp().value
        }
        return PlaceholderConfig(size = Size(w, h), verticalAlign = PlaceholderVerticalAlign.Center)
    }
}
