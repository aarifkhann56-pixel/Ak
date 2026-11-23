package com.example

import com.example.ui.components.MarkdownSegment
import com.example.ui.components.parseMarkdownSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun parseMarkdownSegments_extractsProseAndCodeBlocks() {
        val sample = """
            Here is a Compose button:
            ```kotlin
            Button(onClick = {}) { Text("Go") }
            ```
            Done!
        """.trimIndent()

        val segments = parseMarkdownSegments(sample)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MarkdownSegment.Prose)
        val codeSeg = segments[1] as MarkdownSegment.CodeBlock
        assertEquals("kotlin", codeSeg.language)
        assertEquals("""Button(onClick = {}) { Text("Go") }""", codeSeg.code)
        assertTrue(segments[2] is MarkdownSegment.Prose)
    }

    @Test
    fun parseMarkdownSegments_handlesUnclosedStreamingFence() {
        val streaming = "```gradle\nimplementation(libs.firebase.ai)"
        val segments = parseMarkdownSegments(streaming)
        assertEquals(1, segments.size)
        val codeSeg = segments[0] as MarkdownSegment.CodeBlock
        assertEquals("gradle", codeSeg.language)
        assertEquals("implementation(libs.firebase.ai)", codeSeg.code)
    }
}
