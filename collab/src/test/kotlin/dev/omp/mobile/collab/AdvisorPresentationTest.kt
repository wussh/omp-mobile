package dev.omp.mobile.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorPresentationTest {
    private val json = Json

    private fun details(raw: String): JsonElement = json.parseToJsonElement(raw)

    private fun text(raw: String): MessageContent = MessageContent.Text(raw)

    @Test
    fun structuredNotesWinOverContentAndKeepMetadata() {
        val card = advisorCard(
            details = details(
                """
                {"notes":[
                  {"note":"Add a retry around the fetch.","severity":"concern","advisor":"reviewer","turn":3,"turnsAgo":1},
                  {"note":"Prefer `useState` here.","advisor":"default"}
                ]}
                """.trimIndent(),
            ),
            content = text("<advisory severity=\"blocker\">stale content</advisory>"),
        )

        assertEquals(2, card.notes.size)
        assertEquals("Add a retry around the fetch.", card.notes[0].text)
        assertEquals(AdvisorSeverity.Concern, card.notes[0].severity)
        assertEquals("Concern", card.notes[0].severityLabel)
        assertEquals("reviewer", card.notes[0].advisor)
        assertEquals(1, card.notes[0].turnsAgo)
        assertEquals("Prefer `useState` here.", card.notes[1].text)
        assertNull(card.notes[1].severity)
        assertEquals(0, card.blockerCount)
    }

    @Test
    fun unknownSeverityStaysRawInsteadOfBeingFabricated() {
        val card = advisorCard(
            details = details("""{"notes":[{"note":"Urgent look.","severity":"urgent"}]}"""),
            content = text(""),
        )

        val note = card.notes.single()
        assertNull(note.severity)
        assertEquals("urgent", note.severityName)
        assertEquals("urgent", note.severityLabel)
        assertEquals(0, card.blockerCount)
    }

    @Test
    fun malformedDetailsFallBackToWrappedContent() {
        val card = advisorCard(
            details = details("""{"notes":[{"note":42},"not-an-object"]}"""),
            content = text(
                """
                <advisory advisor="security" severity="blocker" guidance="weigh, don't blindly obey">
                Validate the redirect target &amp; keep `&lt;script&gt;` inert.
                </advisory>
                """.trimIndent(),
            ),
        )

        assertTrue(card.fromContent)
        val note = card.notes.single()
        assertEquals(AdvisorSeverity.Blocker, note.severity)
        assertEquals("security", note.advisor)
        assertEquals("Validate the redirect target & keep `<script>` inert.", note.text)
    }

    @Test
    fun emptyNotesArrayFallsBackToContent() {
        val card = advisorCard(
            details = details("""{"notes":[]}"""),
            content = text(
                """
                <advisory severity="nit">
                Rename this variable.
                </advisory>
                """.trimIndent(),
            ),
        )

        assertTrue(card.fromContent)
        assertEquals("Rename this variable.", card.notes.single().text)
    }

    @Test
    fun legacyBatchUnwrapsEveryAdvisoryAndKeepsMarkdown() {
        val card = advisorCard(
            details = null,
            content = text(
                """
                <advisory advisor="perf" severity="nit" guidance="weigh, don't blindly obey">
                Cache the **parsed** value:

                ```kotlin
                val value = parse(input)
                ```
                </advisory>
                <advisory severity="blocker">
                The branch is unreachable.
                </advisory>
                """.trimIndent(),
            ),
        )

        assertTrue(card.fromContent)
        assertEquals(2, card.notes.size)
        assertEquals("perf", card.notes[0].advisor)
        assertEquals(AdvisorSeverity.Nit, card.notes[0].severity)
        assertTrue(card.notes[0].text.contains("Cache the **parsed** value:"))
        assertTrue(card.notes[0].text.contains("```kotlin"))
        assertEquals(AdvisorSeverity.Blocker, card.notes[1].severity)
        assertEquals(1, card.blockerCount)
    }

    @Test
    fun advisoryLookingLinesInsideFencedCodeArePreserved() {
        val body = """
            <advisory severity="concern" guidance="weigh, don't blindly obey">
            Document the wire format:

            ```
            <advisory severity="blocker">
            literal example
            </advisory>
            ```
            </advisory>
        """.trimIndent()

        val card = advisorCard(details = null, content = text(body))

        val note = card.notes.single()
        assertEquals(AdvisorSeverity.Concern, note.severity)
        assertTrue(note.text.contains("<advisory severity=\"blocker\">"))
        assertTrue(note.text.contains("literal example"))
        assertTrue(note.text.contains("</advisory>"))
        assertTrue(note.text.endsWith("```"))
    }

    @Test
    fun contentWithoutWrappersIsStillReadable() {
        val card = advisorCard(details = null, content = text("Keep the fallback **visible**."))

        assertTrue(card.fromContent)
        assertEquals("Keep the fallback **visible**.", card.notes.single().text)
    }

    @Test
    fun unterminatedAdvisoryKeepsItsBody() {
        val card = advisorCard(
            details = null,
            content = text("<advisory severity=\"nit\">\nCheck the empty state."),
        )

        assertEquals("Check the empty state.", card.notes.single().text)
    }

    @Test
    fun noHostTextProducesAnEmptyCard() {
        val card = advisorCard(details = null, content = text("   "))

        assertTrue(card.notes.isEmpty())
        assertEquals(0, card.blockerCount)
    }

    @Test
    fun blockContentIgnoresNonTextBlocks() {
        val content = MessageContent.Blocks(
            listOf(
                ContentBlock.Text(
                    """
                    <advisory>
                    Keep **this**.
                    </advisory>
                    """.trimIndent(),
                ),
                ContentBlock.Thinking("internal"),
            ),
        )

        val card = advisorCard(details = null, content = content)

        assertEquals("Keep **this**.", card.notes.single().text)
    }

    @Test
    fun proseOutsideAdvisoriesIsNotDropped() {
        val card = advisorCard(
            details = null,
            content = text("Batch update\n<advisory severity=\"nit\">\nOne note.\n</advisory>"),
        )

        assertEquals(2, card.notes.size)
        assertEquals("Batch update", card.notes[0].text)
        assertEquals("One note.", card.notes[1].text)
    }

    @Test
    fun structuredNoteTextIsPreservedVerbatim() {
        val card = advisorCard(
            details = details(
                """{"notes":[{"note":"Use <advisory> only as a literal example.","severity":"nit"}]}""",
            ),
            content = text(""),
        )

        assertEquals("Use <advisory> only as a literal example.", card.notes.single().text)
    }

    @Test
    fun partiallyMalformedDetailsKeepReadableNotesWhenBodyIsMissing() {
        val card = advisorCard(
            details = details("""{"notes":[{"note":"Keep this one.","severity":"nit"},{"note":42},7]}"""),
            content = text(""),
        )

        assertEquals("Keep this one.", card.notes.single().text)
        assertEquals(AdvisorSeverity.Nit, card.notes.single().severity)
    }

    @Test
    fun blockCountsWorstSeverity() {
        val card = advisorCard(
            details = details(
                """{"notes":[
                  {"note":"a","severity":"blocker"},
                  {"note":"b","severity":"blocker"},
                  {"note":"c","severity":"concern"}
                ]}""",
            ),
            content = text(""),
        )

        assertEquals(2, card.blockerCount)
    }
}
