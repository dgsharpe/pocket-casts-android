package au.com.shiftyjelly.pocketcasts.repositories.mimi

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.db.dao.TranscriptDao
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Client for mimi-hub on the server (neural_audio/hub/mimi_hub.py), which converts episodes to `.mimi`
 * (Mimi neural codec, ~2 MB/hour) plus a WebVTT transcript, keyed by the Pocket Casts episode UUID.
 *
 * - Downloads: [substituteForDownload] asks the hub for the episode, requests a conversion if needed and
 *   waits for it (bounded), then points the download at the `.mimi`. Any failure keeps the original.
 * - Streaming: [streamOverride] uses the `.mimi` when the hub already has it, else asks for a conversion.
 * - Transcripts: episodes known to be ready get the hub's VTT as an extra transcript row.
 * - Tracking-prefix redirects are stripped from original URLs when that setting is on ([cleanUrl]).
 *
 * The hub parts are disabled when the hub URL setting is blank.
 */
@Singleton
class MimiHub @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: Settings,
    private val transcriptDao: TranscriptDao,
) {
    private val prefs = context.getSharedPreferences("mimi_hub", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val base: String get() = settings.mimiHubUrl.value.trim().trimEnd('/')

    val isEnabled: Boolean get() = base.isNotEmpty()

    fun mimiUrl(uuid: String) = "$base/v1/episodes/$uuid.mimi"

    fun vttUrl(uuid: String) = "$base/v1/episodes/$uuid.vtt"

    fun isKnownReady(uuid: String): Boolean = prefs.getBoolean(uuid, false)

    /** The hub's record for [uuid]; state "missing" if the hub doesn't know it, null if unreachable. */
    fun status(uuid: String): JSONObject? = call(Request.Builder().url("$base/v1/episodes/$uuid").build())

    /** The hub's ad / self-promotion segments for [uuid] (JSON); null if none yet or unreachable. */
    fun segmentsJson(uuid: String): String? = try {
        client.newCall(Request.Builder().url("$base/v1/episodes/$uuid.segments.json").build()).execute().use { r ->
            if (r.isSuccessful) r.body.string() else null
        }
    } catch (e: Exception) {
        Timber.i("mimi-hub segments unreachable: %s", e.message)
        null
    }

    private val streamingMimi = ConcurrentHashMap.newKeySet<String>()

    /** Whether the last stream of [uuid] was the hub's `.mimi` (rather than the original). */
    fun isStreamingMimi(uuid: String): Boolean = streamingMimi.contains(uuid)

    /** [priority] 0 = about to be streamed (jumps the hub's queue), 1 = download. */
    private fun requestConversion(episode: PodcastEpisode, url: String, priority: Int): JSONObject? {
        val body = JSONObject()
            .put("url", url)
            .put("id", episode.uuid)
            .put("title", episode.title)
            .put("priority", priority)
            .toString()
            .toRequestBody("application/json".toMediaType())
        return call(Request.Builder().url("$base/v1/convert").post(body).build())
    }

    private fun call(request: Request): JSONObject? = try {
        client.newCall(request).execute().use { r ->
            when {
                r.code == 404 -> JSONObject().put("state", "missing")
                r.isSuccessful -> JSONObject(r.body.string())
                else -> null.also { Timber.w("mimi-hub %s -> HTTP %d", request.url, r.code) }
            }
        }
    } catch (e: Exception) {
        Timber.i("mimi-hub unreachable: %s", e.message)
        null
    }

    /**
     * Blocking; for DownloadEpisodeWorker. Returns [episode] pointed at the hub's `.mimi` (with fileType
     * audio/x-mimi so it's saved as `<uuid>.mimi`) as soon as the hub has started encoding it: the hub
     * serves the file progressively, so the download runs alongside the conversion (~1-2 min per hour of
     * audio). Otherwise (hub unreachable, failed, or not started within [maxWaitMs]) the original episode,
     * minus tracking redirects if that setting is on.
     */
    fun substituteForDownload(
        episode: BaseEpisode,
        maxWaitMs: Long = 3 * 60_000L,
        isStopped: () -> Boolean = { false },
    ): BaseEpisode {
        if (episode !is PodcastEpisode) return episode
        val original = episode.downloadUrl?.takeIf { it.isNotBlank() } ?: return episode
        val clean = cleanUrl(original)
        val fallback = if (clean != original) episode.copy(downloadUrl = clean) else episode
        if (!isEnabled) return fallback
        var s = status(episode.uuid) ?: return fallback
        // a user-started download jumps the hub's queue (also bumps a waiting Up Next prefetch)
        if (s.optString("state") in setOf("missing", "failed", "queued")) {
            s = requestConversion(episode, original, priority = 0) ?: return fallback
        }
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (s.optString("state") !in setOf("encoding", "transcribing", "ready", "failed")) {
            if (System.currentTimeMillis() > deadline || isStopped()) return fallback
            Thread.sleep(2_000)
            s = status(episode.uuid) ?: return fallback
        }
        // EpisodeDownloader rejects podcast files under 150 KB; .mimi is 4.4 kb/s, so < ~4.5 min episodes
        if (s.optString("state") == "failed" || s.optLong("mimi_bytes") < 160_000) return fallback
        attachTranscriptWhenReady(episode.uuid)
        Timber.i("mimi-hub: downloading %s as .mimi (%d bytes)", episode.uuid, s.optLong("mimi_bytes"))
        return episode.copy(downloadUrl = mimiUrl(episode.uuid), fileType = MIME_TYPE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val awaitingTranscript = ConcurrentHashMap.newKeySet<String>()

    /** Polls the hub in the background and attaches its transcript once the episode is fully ready. */
    fun attachTranscriptWhenReady(uuid: String) {
        if (isKnownReady(uuid)) {
            scope.launch { attachTranscript(uuid) }
            return
        }
        if (!awaitingTranscript.add(uuid)) return
        scope.launch {
            try {
                repeat(TRANSCRIPT_POLLS) {
                    when (status(uuid)?.optString("state")) {
                        "ready" -> {
                            markReady(uuid)
                            attachTranscript(uuid)
                            return@launch
                        }

                        "failed", "missing" -> return@launch

                        else -> delay(10_000)
                    }
                }
            } finally {
                awaitingTranscript.remove(uuid)
            }
        }
    }

    private val prefetchRequested = ConcurrentHashMap.newKeySet<String>()

    /**
     * For queued episodes (MimiQueuePrefetcher): makes sure the hub has, or is making, [episode] at normal
     * priority, and attaches its transcript once ready. A failed conversion is re-requested at most once
     * per app session. Blocking network calls; call from Dispatchers.IO.
     */
    suspend fun prefetch(episode: PodcastEpisode) {
        if (!isEnabled || isKnownReady(episode.uuid)) return
        val url = episode.downloadUrl?.takeIf { it.isNotBlank() } ?: return
        when (status(episode.uuid)?.optString("state")) {
            "ready" -> {
                markReady(episode.uuid)
                attachTranscript(episode.uuid)
            }

            "missing" -> {
                prefetchRequested.add(episode.uuid)
                requestConversion(episode, url, priority = 1)
            }

            // retried once per session; if this session's own request failed, leave it
            "failed" -> if (prefetchRequested.add(episode.uuid)) requestConversion(episode, url, priority = 1)

            else -> Unit // unreachable, or queued / converting
        }
    }

    /** [url] without tracking-prefix redirects, if that setting is on. */
    fun cleanUrl(url: String): String = if (settings.stripTrackingRedirects.value) TrackingRedirects.strip(url) else url

    /** What to stream instead of the episode's own URL (null: use it as is). */
    data class StreamOverride(val url: String, val contentType: String?, val isMimi: Boolean)

    /**
     * For streaming: the hub's `.mimi`, even while the hub is still making it. Asks for a priority conversion
     * if needed and waits (≤ ~10 s) for the hub to start encoding; the hub then serves the file as it is
     * encoded (~36x real time) and serves seeks ahead of the encoder first. Falls back to the original URL
     * (minus tracking redirects, if that setting is on) when the hub is off, unreachable or can't start.
     */
    suspend fun streamOverride(episode: BaseEpisode): StreamOverride? = withContext(Dispatchers.IO) {
        if (episode !is PodcastEpisode) return@withContext null
        val original = episode.downloadUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (isEnabled) {
            var state = status(episode.uuid)?.optString("state")
            if (state == "missing" || state == "failed" || state == "queued") { // queued: bump a prefetch
                state = requestConversion(episode, original, priority = 0)?.optString("state")
            }
            var waits = 0
            while (state in STARTING && waits++ < 20) {
                delay(500)
                state = status(episode.uuid)?.optString("state")
            }
            if (state in STREAMABLE) {
                attachTranscriptWhenReady(episode.uuid)
                streamingMimi.add(episode.uuid)
                return@withContext StreamOverride(mimiUrl(episode.uuid), MIME_TYPE, isMimi = true)
            }
        }
        streamingMimi.remove(episode.uuid)
        cleanUrl(original).takeIf { it != original }?.let { StreamOverride(it, null, isMimi = false) }
    }

    private fun markReady(uuid: String) {
        prefs.edit().putBoolean(uuid, true).apply()
    }

    /** The hub transcript row for [uuid], if the hub is known to have it. */
    fun transcriptFor(uuid: String): Transcript? = if (isEnabled && isKnownReady(uuid)) {
        Transcript(episodeUuid = uuid, url = vttUrl(uuid), type = "text/vtt", isGenerated = false)
    } else {
        null
    }

    /** Adds the hub transcript to the episode's stored transcripts (keeping the existing ones). */
    suspend fun attachTranscript(uuid: String) {
        val ours = transcriptFor(uuid) ?: return
        val existing = transcriptDao.observeTranscripts(uuid).first()
        if (existing.none { it.url == ours.url }) transcriptDao.replaceAll(existing + ours)
    }

    companion object {
        const val MIME_TYPE = "audio/x-mimi"
        private val HUB_VTT = Regex(".*/v1/episodes/[A-Za-z0-9_-]+\\.vtt$")

        /**
         * True for mimi-hub transcripts. Their cue times are on the timeline of the hub's `.mimi`, which is
         * what plays, so they can be highlighted / seeked by playback position directly (no fingerprinting).
         */
        fun isHubTranscriptUrl(url: String): Boolean = HUB_VTT.matches(url)
        private const val TRANSCRIPT_POLLS = 120 // x 10 s
        private val STARTING = setOf("queued", "starting", "downloading")
        private val STREAMABLE = setOf("encoding", "transcribing", "ready")
    }
}
