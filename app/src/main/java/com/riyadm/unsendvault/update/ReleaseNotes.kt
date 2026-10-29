package com.riyadm.unsendvault.update

/** Turns GitHub release markdown into a short plain-text excerpt. Crude on purpose; HTML is never rendered. */
object ReleaseNotes {
    const val DEFAULT_MAX_CHARS = 600

    private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val codeFence = Regex("""```.*?(```|$)""", RegexOption.DOT_MATCHES_ALL)
    private val htmlTag = Regex("""<[^>\n]{1,200}>""")
    private val image = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val heading = Regex("""^\s{0,3}#{1,6}(\s+|$)""")
    private val bullet = Regex("""^\s*[-*+]\s+""")
    private val emphasis = Regex("""(\*\*|__|\*|`)""")
    private val tableRow = Regex("""^\s*\|.*\|\s*$""")
    private val bareUrl = Regex("""https?://\S+""")
    private val whatChanged = Regex("""^\s{0,3}#{1,6}\s*what'?s? changed\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val nextHeading = Regex("""^\s{0,3}#{1,6}\s""", RegexOption.MULTILINE)

    /**
     * Prefers the "What changed" section when the notes have one (CI-generated notes start with install instructions),
     * then strips markdown and truncates to [maxChars] on a word boundary.
     */
    fun excerpt(body: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        var text = body.replace("\r\n", "\n")
        whatChanged.find(text)?.let { match ->
            val rest = text.substring(match.range.last + 1)
            text = nextHeading.find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
        }
        text = text.replace(htmlComment, "").replace(codeFence, "")
        val lines = text.lines().mapNotNull { raw ->
            if (tableRow.matches(raw)) return@mapNotNull null
            var line = raw.replace(image, "$1").replace(link, "$1").replace(htmlTag, "")
            line = line.replace(heading, "").replace(bullet, "• ").replace(emphasis, "").replace(bareUrl, "")
            line.trimEnd()
        }
        val cleaned = lines.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
        if (cleaned.length <= maxChars) return cleaned
        val cut = cleaned.substring(0, maxChars)
        val boundary = cut.lastIndexOfAny(charArrayOf(' ', '\n')).takeIf { it > maxChars / 2 } ?: maxChars
        return cut.substring(0, boundary).trimEnd() + "…"
    }
}
