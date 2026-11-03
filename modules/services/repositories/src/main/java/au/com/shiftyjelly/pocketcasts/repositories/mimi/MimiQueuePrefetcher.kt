package au.com.shiftyjelly.pocketcasts.repositories.mimi

import android.content.Context
import android.net.NetworkCapabilities
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.NetworkConnectionWatcher
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Has mimi-hub convert everything in Up Next ahead of time: whenever the queue changes, when Wi-Fi comes back, and
 * while some are still being made (30 s, then 2, 5 and 15 min apart) the playing episode and the first [MAX_EPISODES]
 * queued ones are requested at normal priority, so by the time
 * one is played or downloaded its `.mimi` and transcript are usually ready. Streams still jump the hub's queue.
 * Ready episodes also get their ad / self-promotion segments cached, so skipping works offline.
 */
@Singleton
class MimiQueuePrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: Settings,
    private val networkConnectionWatcher: NetworkConnectionWatcher,
    private val upNextQueue: UpNextQueue,
    private val mimiHub: MimiHub,
    private val mimiSegments: MimiSegments,
    private val episodeManager: EpisodeManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val sweepLock = Mutex()
    private var retryJob: Job? = null
    private var retryStep = 0 // into RETRY_DELAYS_MS: each sweep that still finds work pending waits longer

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { fixDownloadedMimiSizes() }
        scope.launch(Dispatchers.IO) {
            upNextQueue.changesFlow.debounce(3_000).collect { sweep(fresh = true) }
        }
        scope.launch(Dispatchers.IO) {
            networkConnectionWatcher.networkCapabilities
                .map { it?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .distinctUntilChanged()
                .filter { it }
                .debounce(3_000)
                .collect { sweep() }
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

    /** [fresh]: after a change of the queue, the retries start again from the shortest delay. */
    private suspend fun sweep(fresh: Boolean = false): Unit = sweepLock.withLock {
        if (fresh) retryStep = 0
        if (!mimiHub.isEnabled) return@withLock
        if (settings.mimiPrefetchOnlyOnWifi.value && !Network.isWifiConnection(context)) return@withLock
        try {
            val current = upNextQueue.currentEpisode as? PodcastEpisode
            val queue = upNextQueue.allEpisodes.filterIsInstance<PodcastEpisode>()
            val candidates = (listOfNotNull(current) + queue).distinctBy { it.uuid }.take(MAX_EPISODES)
            var hasPending = false
            candidates.forEach { episode ->
                val pending = mimiHub.prefetch(episode)
                if (mimiHub.isKnownReady(episode.uuid)) {
                    mimiSegments.get(episode.uuid)
                } else if (pending) {
                    hasPending = true
                }
            }
            retryJob?.cancel()
            if (hasPending) {
                // backing off: a job stuck behind a long queue (or for good) mustn't be polled every 30 s for hours,
                // nor wake a server that scales to zero (billed per start) that often
                val wait = RETRY_DELAYS_MS[retryStep.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                retryStep++
                retryJob = scope.launch(Dispatchers.IO) {
                    delay(wait)
                    sweep()
                }
            } else {
                retryStep = 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "mimi-hub prefetch sweep failed")
        }
    }

    private companion object {
        const val MAX_EPISODES = 30
        val RETRY_DELAYS_MS = longArrayOf(30_000L, 2 * 60_000L, 5 * 60_000L, 15 * 60_000L)
    }
}
