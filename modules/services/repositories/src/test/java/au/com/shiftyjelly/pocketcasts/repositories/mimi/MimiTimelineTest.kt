package au.com.shiftyjelly.pocketcasts.repositories.mimi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimiTimelineTest {
    private fun ad(start: Long, end: Long) = MimiSegments.Segment(start, end, isAd = true, label = "ad")
    private fun promo(start: Long, end: Long) = MimiSegments.Segment(start, end, isAd = false, label = "promo")

    private val preRoll = ad(0, 60_000)
    private val midRoll = ad(600_000, 700_000)
    private val plug = promo(1_000_000, 1_020_000)
    private val trimmed = MimiTimeline(listOf(preRoll, midRoll, plug), cuts = listOf(preRoll, midRoll))

    @Test
    fun `without cuts both directions are the identity`() {
        val t = MimiTimeline(listOf(midRoll))
        assertFalse(t.isTrimmed)
        assertEquals(650_000L, t.toTrimmed(650_000))
        assertEquals(650_000L, t.toReal(650_000))
        assertEquals(listOf(midRoll), t.shown)
    }

    @Test
    fun `cut time is removed before and inside cuts`() {
        assertTrue(trimmed.isTrimmed)
        assertEquals(0L, trimmed.toTrimmed(30_000)) // inside the pre-roll: where it was
        assertEquals(540_000L, trimmed.toTrimmed(600_000)) // start of the mid-roll
        assertEquals(540_000L, trimmed.toTrimmed(650_000)) // inside it
        assertEquals(540_000L, trimmed.toTrimmed(700_000)) // its end
        assertEquals(800_000L, trimmed.toTrimmed(960_000))
    }

    @Test
    fun `trimmed positions map back to real ones outside cuts`() {
        assertEquals(60_000L, trimmed.toReal(0)) // after the pre-roll
        assertEquals(700_000L, trimmed.toReal(540_000)) // where the mid-roll was: just after it
        assertEquals(960_000L, trimmed.toReal(800_000))
        for (real in listOf(60_000L, 300_000L, 700_000L, 960_000L, 2_000_000L)) {
            assertEquals(real, trimmed.toReal(trimmed.toTrimmed(real)))
        }
    }

    @Test
    fun `skip forward across a cut lands past it`() {
        val now = 590_000L // 10 s before the mid-roll
        assertEquals(720_000L, trimmed.toReal(trimmed.toTrimmed(now) + 30_000))
        assertEquals(590_000L, trimmed.toReal(trimmed.toTrimmed(720_000) - 30_000)) // and back over it
    }

    @Test
    fun `segments still shown are moved onto the trimmed timeline`() {
        assertEquals(listOf(promo(840_000, 860_000)), trimmed.shown)
    }

    @Test
    fun `overlapping cuts are merged`() {
        val t = MimiTimeline(listOf(ad(100, 300), ad(200, 400)), cuts = listOf(ad(100, 300), ad(200, 400)))
        assertEquals(200L, t.toTrimmed(500))
        assertEquals(400L, t.toReal(100))
    }
}
