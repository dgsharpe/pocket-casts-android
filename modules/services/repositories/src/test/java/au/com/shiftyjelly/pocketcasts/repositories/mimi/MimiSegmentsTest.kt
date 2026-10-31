package au.com.shiftyjelly.pocketcasts.repositories.mimi

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.UserSetting
import java.io.File
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MimiSegmentsTest {
    private val settings = mock<Settings>()
    private val mimiHub = mock<MimiHub>()
    private lateinit var segments: MimiSegments

    private val episode = PodcastEpisode(
        uuid = "e1",
        publishedDate = Date(),
        downloadStatus = EpisodeDownloadStatus.Downloaded,
        downloadedFilePath = "/somewhere/e1.mimi",
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "mimi_segments").apply { mkdirs() }.resolve("e1.json").writeText(
            """{"model": "test", "segments": [
                {"start": 1.1, "end": 61.4, "kind": "ad", "label": "pre-roll"},
                {"start": 600.0, "end": 700.0, "kind": "ad", "label": "mid-roll"},
                {"start": 1000.0, "end": 1012.0, "kind": "promo", "label": "membership"}]}""",
        )
        whenever(settings.mimiSkipAds).thenReturn(UserSetting.Mock(true, mock()))
        whenever(settings.mimiSkipPromos).thenReturn(UserSetting.Mock(false, mock()))
        segments = MimiSegments(context, settings, mimiHub)
    }

    private fun play(vararg positionsMs: Long) = positionsMs.map { segments.toSkip(episode, it)?.label }

    @Test
    fun `skips a pre-roll when playback starts`() {
        assertEquals(listOf(null, "pre-roll"), play(400, 1_400))
    }

    @Test
    fun `skips an ad that playback runs into`() {
        assertEquals(listOf(null, null, "mid-roll", null), play(598_000, 599_000, 600_200, 700_100))
    }

    @Test
    fun `plays an ad that was seeked into`() {
        assertEquals(listOf(null, null, null), play(300_000, 610_000, 611_000))
    }

    @Test
    fun `skips promos only when that setting is on`() {
        assertNull(play(999_500, 1_000_500).last())
        whenever(settings.mimiSkipPromos).thenReturn(UserSetting.Mock(true, mock()))
        assertEquals("membership", play(999_500, 1_000_500).last())
    }

    @Test
    fun `ignores segments when the original file plays`() {
        val original = episode.copy(downloadedFilePath = "/somewhere/e1.mp3")
        segments.toSkip(original, 599_000)
        assertNull(segments.toSkip(original, 600_200))
    }
}
