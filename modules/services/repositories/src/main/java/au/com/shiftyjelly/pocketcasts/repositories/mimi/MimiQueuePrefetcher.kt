package au.com.shiftyjelly.pocketcasts.repositories.mimi

import android.content.Context
import android.net.NetworkCapabilities
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.NetworkConnectionWatcher
import au.com.shiftyjelly.pocketcasts.utils.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    private var retryJob: kotlinx.coroutines.Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { fixDownloadedMimiSizes() }
        scope.launch(Dispatchers.IO) {
            upNextQueue.changesFlow.debounce(3_000).collect { sweep() }
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

    private suspend fun sweep(): Unit = sweepLock.withLock {
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
                retryJob = scope.launch(Dispatchers.IO) {
                    delay(RETRY_DELAY_MS)
                    sweep()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "mimi-hub prefetch sweep failed")
        }
    }

    private companion object {
        const val MAX_EPISODES = 30
        const val RETRY_DELAY_MS = 30_000L
    }
}
