package dev.omp.mobile.collab

import org.junit.Assert.assertEquals
import org.junit.Test

class AsyncResultPresentationTest {
    @Test
    fun hostEnvelopeDoesNotHideNestedJobOutput() {
        val output = "Background job BuildCheck has completed.\n" +
            "<task-result id=\"BuildCheck\" status=\"completed\">\n<output>\n" +
            "42 assertions passed.\n    indented output\n<result>literal</result>\n" +
            "</output>\n</task-result>"
        assertEquals(output, asyncResultText("<system-notice>\n$output\n</system-notice>"))
    }

    @Test
    fun ordinaryOutputAndTagLookingCodeAreNotStripped() {
        val output = "    keep indentation\n```xml\n<system-notice>\nexample\n</system-notice>\n```"
        assertEquals(output, asyncResultText(output))
    }

    @Test
    fun malformedEnvelopeStillShowsAllAvailableOutput() {
        val output = "<system-notice>\nA job stopped before closing its output."
        assertEquals(output, asyncResultText(output))
    }

    @Test
    fun windowsLineEndingsKeepTheResultReadable() {
        assertEquals("    output", asyncResultText("<system-notice>\r\n    output\r\n</system-notice>"))
    }

    @Test
    fun emptyHostEnvelopeHasNoDisplayableOutput() {
        assertEquals("", asyncResultText("<system-notice>\n</system-notice>"))
    }
}
