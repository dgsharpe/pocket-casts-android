package au.com.shiftyjelly.pocketcasts.repositories.mimi

import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MimiTranscriptFoldTest {
    private fun text(value: String, start: Long) = TranscriptEntry.Text(value, startTimeMs = start, endTimeMs = start + 1_000)

    private val ad = MimiSegments.Segment(10_000, 20_000, isAd = true, label = "Indeed")
    private val label: (MimiSegments.Segment) -> String = { "fold ${it.label}" }

    @Test
    fun `cut lines become one line and the speaker after it is labelled again`() {
        val entries = listOf(
            Speaker("Speaker 1"),
            text("hello", 0),
            text("before", 9_000),
            Speaker("Speaker 3"),
            text("this episode is brought to you by", 10_000),
            text("indeed", 15_000),
            Speaker("Speaker 1"),
            text("back", 20_000),
        )
        assertEquals(
            listOf(
                Speaker("Speaker 1"),
                text("hello", 0),
                text("before", 9_000),
                TranscriptEntry.Text("fold Indeed", startTimeMs = 10_000, endTimeMs = 20_000),
                Speaker("Speaker 1"),
                text("back", 20_000),
            ),
            entries.foldMimiSegments(listOf(ad), label),
        )
    }

    @Test
    fun `without speakers or cuts nothing else changes`() {
        val entries = listOf(text("a", 0), text("ad", 12_000), text("b", 25_000))
        assertEquals(
            listOf(text("a", 0), TranscriptEntry.Text("fold Indeed", startTimeMs = 10_000, endTimeMs = 20_000), text("b", 25_000)),
            entries.foldMimiSegments(listOf(ad), label),
        )
        assertSame(entries, entries.foldMimiSegments(emptyList(), label))
    }

    @Test
    fun `untimed lines are never folded`() {
        val entries = listOf(TranscriptEntry.Text("no times"))
        assertEquals(entries, entries.foldMimiSegments(listOf(ad), label))
    }
}
