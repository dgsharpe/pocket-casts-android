package au.com.shiftyjelly.pocketcasts.repositories.mimi

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Has mimi-hub convert everything in Up Next ahead of time: whenever the queue changes (and every few minutes
 * as a backstop) the first [MAX_EPISODES] queued episodes are requested at normal priority, so by the time
 * one is played or downloaded its `.mimi` and transcript are usually ready. Streams still jump the hub's queue.
 */
@Singleton
class MimiQueuePrefetcher @Inject constructor(
    private val upNextQueue: UpNextQueue,
    private val mimiHub: MimiHub,
    private val episodeManager: EpisodeManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val sweepLock = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { fixDownloadedMimiSizes() }
        scope.launch(Dispatchers.IO) {
            upNextQueue.changesFlow.debounce(3_000).collect { sweep() }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5.minutes)
                sweep()
            }
        }
    }

    /**
     * Storage screens sum episode.sizeInBytes, which comes from the original enclosure. Downloads made before
     * DownloadEpisodeWorker recorded the real size (or any that drifted) get the `.mimi` file's actual size.
     */
    private suspend fun fixDownloadedMimiSizes() {
        try {
            episodeManager.findDownloadedEpisodesFlow().first()
                .filter { it.downloadedFilePath?.endsWith(".mimi") == true }
                .forEach { episode ->
                    val size = episode.downloadedFilePath?.let { File(it).length() } ?: 0L
                    if (size > 0 && episode.sizeInBytes != size) {
                        episodeManager.updateSizeInBytesBlocking(episode, size)
                        Timber.i("mimi: corrected size of %s to %d bytes", episode.uuid, size)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "mimi: size correction failed")
        }
    }

    private suspend fun sweep() = sweepLock.withLock {
        if (!mimiHub.isEnabled) return@withLock
        try {
            upNextQueue.allEpisodes
                .filterIsInstance<PodcastEpisode>()
                .take(MAX_EPISODES)
                .forEach { mimiHub.prefetch(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "mimi-hub prefetch sweep failed")
        }
    }

    private companion object {
        const val MAX_EPISODES = 30
    }
}
