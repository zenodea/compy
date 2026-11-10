package dev.compy.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {
    @Test fun splitsBlocks() {
        val blocks = markdownBlocks(
            """
            ## Plan
            First line
            still first

            - one
              - nested
            1. numbered
            ```kotlin
            val x = 1
            ```
            > quoted
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, "Plan"),
                MdBlock.Paragraph("First line\nstill first"),
                MdBlock.Item("•", 0, "one"),
                MdBlock.Item("•", 1, "nested"),
                MdBlock.Item("1.", 0, "numbered"),
                MdBlock.Code("val x = 1"),
                MdBlock.Quote("quoted"),
            ),
            blocks,
        )
    }

    @Test fun linesUpTables() {
        val blocks = markdownBlocks("| a | long |\n|---|---|\n| bb | c |")
        assertEquals(listOf(MdBlock.Code("a   long\nbb  c")), blocks)
    }

    @Test fun stripsInlineMarks() {
        val text = inlineMarkdown("Run `ls`, **then** *look* at [docs](https://x.dev) or snake_case_name", Color.Gray, Color.Blue)
        assertEquals("Run ls, then look at docs or snake_case_name", text.text)
    }

    @Test fun leavesUnderscoresInNamesAlone() {
        val text = inlineMarkdown("_private_var and foo__bar__baz but _this_ and __that__", Color.Gray, Color.Blue)
        assertEquals("_private_var and foo__bar__baz but this and that", text.text)
    }

    @Test fun onlyWebLinksOpen() {
        val text = inlineMarkdown("[docs](https://x.dev \"Docs\") and [file](file:///etc/passwd)", Color.Gray, Color.Blue)
        assertEquals("docs and file", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://x.dev"), links)
    }

    @Test fun aLinkStartsAtItsOwnBracket() {
        val text = inlineMarkdown("- [x] see arr[0] and [docs](https://x.dev)", Color.Gray, Color.Blue)
        assertEquals("- [x] see arr[0] and docs", text.text)
    }
}
