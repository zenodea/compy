package dev.compy.term

/** Finds file paths in terminal text. Careful, so that ordinary words aren't taken for files. */
object TerminalPaths {
    /** A path and the line it names, or 0 when it names none. */
    data class Hit(val path: String, val line: Int = 0)

    /** Whether text looks like a file's path or name: it has a slash, or ends in an extension. */
    fun candidate(text: String): Boolean {
        if (text.isEmpty() || text.length > 4096 || '\n' in text) return false
        if (':' in text || text.startsWith("www.") || text == "/") return false
        if ('/' in text) return true
        val dot = text.lastIndexOf('.')
        if (dot < 0) return false
        if (dot == 0) return text.length > 1
        val extension = text.substring(dot + 1)
        if (extension.isEmpty() || extension.length > 12 || !extension.all { it.isLetterOrDigit() }) return false
        // A decimal number isn't a file's name.
        return text.substring(0, dot).any { !it.isDigit() }
    }

    /**
     * The path under a cell. [rows] holds one string per screen row, with one character per
     * column. A path that reaches the right edge carries on at the start of the next row, as a
     * long one does in a narrow pane. A quoted path may hold spaces.
     */
    fun at(rows: List<String>, col: Int, row: Int): Hit? {
        if (row !in rows.indices || col !in rows[row].indices) return null
        var first = row
        while (first > 0 && wraps(rows[first - 1], rows[first])) first--
        var last = row
        while (last + 1 < rows.size && wraps(rows[last], rows[last + 1])) last++
        var hit = col
        val line = StringBuilder()
        for (y in first..last) {
            if (y < row) hit += rows[y].length
            line.append(rows[y])
        }
        if (line.length > MAX_LINE) return null
        var i = 0
        while (i < line.length) {
            while (i < line.length && (line[i] == ' ' || line[i] in OPENERS)) i++
            if (i == line.length) break
            val quoted = line[i] in QUOTES
            val delimiter = line[i]
            if (quoted) i++
            val start = i
            while (i < line.length && (if (quoted) line[i] != delimiter else line[i] != ' ')) i++
            var end = i
            if (!quoted) while (end > start && line[end - 1] in TRAILING) end--
            if (hit in start until end) return parse(line.substring(start, end))
            if (quoted && i < line.length) i++
        }
        return null
    }

    private fun wraps(above: String, below: String) =
        above.isNotEmpty() && below.isNotEmpty() && above.last() != ' ' && below.first() != ' '

    /**
     * A path from text the person selected or a program linked, with the line it names:
     * `src/a.kt`, `src/a.kt:12`, `src/a.kt:12:5`, `src/a.kt:12-20`, `file:///x/a.kt#L12`. Null for
     * anything else, such as an address on the web.
     */
    fun parse(text: String): Hit? {
        var rest = text.trim()
        if (rest.startsWith("file://", ignoreCase = true)) {
            // Past the host, which is empty or the machine's name.
            rest = rest.substring("file://".length)
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            rest = rest.substring(slash)
            rest = runCatching { java.net.URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8") }.getOrDefault(rest)
        }
        var line = 0
        // #L42 and #L42-L50, as code hosts write them.
        val hash = rest.lastIndexOf('#')
        if (hash > 0) {
            LINE_MARK.matchEntire(rest.substring(hash + 1))?.let {
                line = it.groupValues[1].toIntOrNull() ?: 0
                rest = rest.substring(0, hash)
            }
        }
        // Compiler-style path:line[:column], and path:line-line as coding agents write a range.
        // No path here has a colon of its own, so the first one ends the path.
        val colon = rest.indexOf(':')
        if (colon == 0) return null
        if (colon > 0) {
            val after = rest.substring(colon + 1)
            val digits = after.takeWhile { it.isDigit() }
            if (digits.isEmpty() || after.any { !it.isDigit() && it != ':' && it != '-' }) return null
            line = digits.toIntOrNull() ?: 0
            rest = rest.substring(0, colon)
        }
        return if (candidate(rest)) Hit(rest, line) else null
    }

    private const val MAX_LINE = 16_384
    private const val OPENERS = "([*"
    private const val QUOTES = "'\"`"
    private const val TRAILING = ",;:)]}.*"
    private val LINE_MARK = Regex("""[Ll](\d+)(?:-[Ll]?\d+)?""")
}
