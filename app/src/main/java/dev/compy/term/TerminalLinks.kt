package dev.compy.term

/** Finds web links in terminal text, including ones a narrow pane wrapped onto several rows. */
object TerminalLinks {
    /** One row's part of a link. [end] is inclusive. */
    data class Segment(val row: Int, val start: Int, val end: Int)

    data class Link(val url: String, val segments: List<Segment>) {
        fun contains(col: Int, row: Int) = segments.any { it.row == row && col in it.start..it.end }
    }

    private val URL = Regex("""https?://[^\s<>"'`|\\^{}]+""")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '*', '_')
    private val NOT_URL = charArrayOf(' ', '<', '>', '"', '\'', '`', '|', '\\', '^', '{', '}')

    /** [rows] holds one string per screen row, with one character per column. */
    fun find(rows: List<String>): List<Link> {
        val links = mutableListOf<Link>()
        var y = 0
        while (y < rows.size) {
            val row = rows[y]
            var nextRow = y + 1
            if ("://" in row) {
                for (match in URL.findAll(row)) {
                    val text = StringBuilder(match.value)
                    val segments = mutableListOf(Segment(y, match.range.first, match.range.last))
                    // A link touching the right edge may carry on at the start of the next row.
                    var at = y
                    var reachedEdge = match.range.last == rows[at].length - 1
                    while (reachedEdge && at + 1 < rows.size) {
                        val next = rows[at + 1]
                        val stop = next.indexOfFirst { it in NOT_URL }
                        val run = if (stop < 0) next.length else stop
                        if (run == 0) break
                        text.append(next, 0, run)
                        at++
                        segments += Segment(at, 0, run - 1)
                        reachedEdge = run == next.length
                    }
                    if (at + 1 > nextRow) nextRow = at + 1
                    links += trimmed(text.toString(), segments)
                }
            }
            y = nextRow
        }
        return links
    }

    /** Sentence punctuation after a link isn't part of it, but its closing brackets may be. */
    private fun trimmed(url: String, segments: MutableList<Segment>): Link {
        var text = url
        while (text.isNotEmpty() && text.last() in TRAILING) {
            if (text.last() == ')' && text.count { it == '(' } >= text.count { it == ')' }) break
            text = text.dropLast(1)
            val last = segments.last()
            if (last.end > last.start) {
                segments[segments.lastIndex] = last.copy(end = last.end - 1)
            } else if (segments.size > 1) {
                segments.removeAt(segments.lastIndex)
            }
        }
        return Link(text, segments)
    }
}
