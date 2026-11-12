package dev.compy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The parts of Markdown agents write: paragraphs, headings, lists, code blocks and tables. */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Item(val marker: String, val depth: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val ITEM = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")

internal fun markdownBlocks(source: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.joinToString("\n"))
        paragraph.clear()
    }
    val lines = source.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                out += MdBlock.Code(code.joinToString("\n"))
            }
            // Tables keep their columns lined up in a monospace block.
            trimmed.startsWith("|") -> {
                flush()
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += lines[i++].trim()
                out += MdBlock.Code(alignTable(rows))
                continue
            }
            line.isBlank() -> flush()
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            ITEM.matches(line) -> {
                flush()
                val m = ITEM.find(line)!!
                val marker = m.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                out += MdBlock.Item(marker, m.groupValues[1].length / 2, m.groupValues[3])
            }
            trimmed.startsWith(">") -> {
                flush()
                out += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }
            else -> paragraph += line
        }
        i++
    }
    flush()
    return out
}

/** Pads a Markdown table's cells so its columns line up, dropping the |---| row. */
private fun alignTable(rows: List<String>): String {
    val cells = rows
        .map { row -> row.trim('|').split('|').map { it.trim() } }
        .filterNot { row -> row.all { it.isEmpty() || it.all { c -> c == '-' || c == ':' } } }
    val widths = IntArray(cells.maxOfOrNull { it.size } ?: 0)
    cells.forEach { row -> row.forEachIndexed { i, c -> widths[i] = maxOf(widths[i], c.length) } }
    return cells.joinToString("\n") { row ->
        row.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd()
    }
}

/** True when the character at [at] is part of a name, where an underscore is only an underscore. */
private fun insideWord(text: String, at: Int) = at in text.indices && (text[at].isLetterOrDigit() || text[at] == '_')

/** Inline Markdown: `code`, **bold**, *italic* and [links](url). */
internal fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                    continue
                }
            }
            text.startsWith("**", i) || (text.startsWith("__", i) && !insideWord(text, i - 1)) -> {
                val mark = text.substring(i, i + 2)
                val end = text.indexOf(mark, i + 2)
                // Underscores inside a name, such as foo__bar__baz, aren't emphasis.
                if (end > i + 2 && (c == '*' || !insideWord(text, end + 2))) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inlineMarkdown(text.substring(i + 2, end), codeBackground, linkColor)) }
                    i = end + 2
                    continue
                }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                !insideWord(text, i - 1) -> {
                val end = text.indexOf(c, i + 1)
                // An underscore with more of the name after it, as in _private_var, isn't a closer.
                if (end > i + 1 && !text[end - 1].isWhitespace() && (c == '*' || !insideWord(text, end + 1))) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                    continue
                }
            }
            c == '[' -> {
                // The first closing bracket must be the one before the address. Looking for any
                // later "](" made "[x] see [docs](url)" one link from the first bracket on.
                val close = text.indexOf(']', i + 1)
                val end = if (close > i && text.startsWith("](", close)) text.indexOf(')', close) else -1
                if (end > close) {
                    val label = text.substring(i + 1, close)
                    // An address may be followed by a title in quotes.
                    val url = text.substring(close + 2, end).trim().substringBefore(' ')
                    val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    // An agent wrote this, so only ever hand web links to other apps.
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(style))) { append(label) }
                    } else {
                        withStyle(style) { append(label) }
                    }
                    i = end + 1
                    continue
                }
            }
        }
        append(c)
        i++
    }
}

@Composable
internal fun MarkdownText(source: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(source) { markdownBlocks(source) }
    val scheme = MaterialTheme.colorScheme
    val codeBg = scheme.surfaceContainerHighest
    val body = MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(inlineMarkdown(block.text, codeBg, scheme.primary), style = body, color = color)
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, codeBg, scheme.primary),
                    style = if (block.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 14).dp)) {
                    Text(block.marker, Modifier.width(if (block.marker == "•") 14.dp else 22.dp), style = body, color = scheme.onSurfaceVariant)
                    Text(inlineMarkdown(block.text, codeBg, scheme.primary), style = body, color = color)
                }
                is MdBlock.Code -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(codeBg)) {
                    Text(
                        block.text,
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            // Room on the right, so the copy button doesn't sit on the first line.
                            .padding(start = 10.dp, top = 8.dp, end = 34.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        softWrap = false,
                    )
                    androidx.compose.foundation.text.selection.DisableSelection {
                        CopyButton(block.text, Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(2.dp))
                    }
                }
                is MdBlock.Quote -> Text(
                    inlineMarkdown(block.text, codeBg, scheme.primary),
                    Modifier.padding(start = 10.dp),
                    style = body,
                    fontStyle = FontStyle.Italic,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
