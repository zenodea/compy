package dev.compy.ui

import androidx.compose.ui.graphics.Color
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
}
