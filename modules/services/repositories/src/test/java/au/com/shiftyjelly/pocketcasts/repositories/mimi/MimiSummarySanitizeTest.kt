package au.com.shiftyjelly.pocketcasts.repositories.mimi

import org.junit.Assert.assertEquals
import org.junit.Test

class MimiSummarySanitizeTest {
    @Test
    fun `keeps the summary's tags without attributes`() {
        assertEquals(
            "<h4>Topics</h4><p>One <strong>two</strong></p><ul><li>three</li></ul>",
            MimiHub.sanitizeSummary("<h4 class=\"x\">Topics</h4><p style=\"color:red\">One <strong>two</strong></p><ul><li>three</li></ul>"),
        )
    }

    @Test
    fun `drops scripts, images, links and handlers`() {
        assertEquals(
            "<p>a  b evil() link</p>",
            MimiHub.sanitizeSummary("<p>a <img src=x onerror=alert(1)> b <script>evil()</script> <a href=\"javascript:x\">link</a></p>"),
        )
    }

    @Test
    fun `leaves escaped text alone`() {
        assertEquals("<p>&lt;b&gt; &amp; 1 &lt; 2</p>", MimiHub.sanitizeSummary("<p>&lt;b&gt; &amp; 1 &lt; 2</p>"))
    }
}
