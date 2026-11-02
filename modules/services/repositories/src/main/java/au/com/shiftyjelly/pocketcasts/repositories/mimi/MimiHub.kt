package au.com.shiftyjelly.pocketcasts.repositories.mimi

import android.content.Context
import java.io.File
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val podcastPrefs = context.getSharedPreferences("mimi_podcasts", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val base: String get() = settings.mimiHubUrl.value.trim().trimEnd('/')

    val isEnabled: Boolean get() = base.isNotEmpty()
    val isSummariesEnabled: Boolean get() = isEnabled && settings.mimiShowSummaries.value

    fun mimiUrl(uuid: String) = "$base/v1/episodes/$uuid.mimi"

    fun vttUrl(uuid: String) = "$base/v1/episodes/$uuid.vtt"

    fun isKnownReady(uuid: String): Boolean = prefs.getBoolean(uuid, false)

    // Per-podcast choices (podcast settings), each Default / On / Off; Default follows the global setting.
    // Compression: play the hub's .mimi. Transcripts & ad marks: the hub's VTT and ad / self-promo segments.
    private fun choice(key: String): MimiConversion = podcastPrefs.getString(key, null)
        ?.let { name -> MimiConversion.entries.firstOrNull { it.name == name } }
        ?: MimiConversion.Default

    private fun setChoice(key: String, value: MimiConversion) {
        podcastPrefs.edit().apply {
            if (value == MimiConversion.Default) remove(key) else putString(key, value.name)
        }.apply()
    }

    private fun MimiConversion.resolve(default: Boolean) = when (this) {
        MimiConversion.On -> true
        MimiConversion.Off -> false
        MimiConversion.Default -> default
    }

    fun compressionFor(podcastUuid: String) = choice(podcastUuid)

    fun setCompression(podcastUuid: String, value: MimiConversion) = setChoice(podcastUuid, value)

    fun transcriptionFor(podcastUuid: String) = choice("$podcastUuid:transcripts")

    fun setTranscription(podcastUuid: String, value: MimiConversion) = setChoice("$podcastUuid:transcripts", value)

    fun compressesPodcast(podcastUuid: String): Boolean = isEnabled && compressionFor(podcastUuid).resolve(settings.mimiConvertByDefault.value)

    fun transcribesPodcast(podcastUuid: String): Boolean = isEnabled && transcriptionFor(podcastUuid).resolve(settings.mimiTranscribeByDefault.value)

    fun compresses(episode: PodcastEpisode) = compressesPodcast(episode.podcastUuid)

    fun transcribes(episode: PodcastEpisode) = transcribesPodcast(episode.podcastUuid)

    /** The hub handles [episode] at all. Transcripts without compression: the hub keeps the original it processed
     * and serves that ([srcUrl]), since a fresh copy from the publisher can carry other (dynamic) ads. */
    fun usesHub(episode: PodcastEpisode) = compresses(episode) || transcribes(episode)

    fun srcUrl(uuid: String) = "$base/v1/episodes/$uuid.src"

    /** This podcast wants the .mimi and the hub makes them (a hub run with MIMI_HUB_COMPRESS=0 doesn't). */
    private fun hubCompresses(episode: PodcastEpisode, status: JSONObject?) = compresses(episode) && status?.optBoolean("compressed", true) != false

    /** The hub can serve the original it processed: kept, or (MIMI_HUB_SOURCES=fetch) re-fetched from the publisher
     * and still the same file. 409 when the publisher's file changed, e.g. different dynamically inserted ads. */
    private fun sourceServable(uuid: String): Boolean = try {
        val request = Request.Builder().url(srcUrl(uuid)).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { r -> r.isSuccessful.also { if (!it) Timber.i("mimi-hub .src: %d", r.code) } }
    } catch (e: Exception) {
        Timber.i("mimi-hub .src unreachable: %s", e.message)
        false
    }

    /** Downloads made from the hub's kept original ([srcUrl]); their timeline is the hub's, like a .mimi's. */
    fun markHubSource(uuid: String) = prefs.edit().putBoolean("src:$uuid", true).apply()

    fun isHubSource(uuid: String) = prefs.getBoolean("src:$uuid", false)

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

    private val streamingHub = ConcurrentHashMap.newKeySet<String>()

    /** Whether the last stream of [uuid] came from the hub (its `.mimi` or its kept original). */
    fun isStreamingHub(uuid: String): Boolean = streamingHub.contains(uuid)

    /** [priority] 0 = about to be streamed (jumps the hub's queue), 1 = download. The original is kept
     * (keep_source) when [episode] isn't to be compressed. */
    private fun requestConversion(episode: PodcastEpisode, url: String, priority: Int): JSONObject? {
        val body = JSONObject()
            .put("url", url)
            .put("id", episode.uuid)
            .put("title", episode.title)
            .put("priority", priority)
            .put("keep_source", !compresses(episode))
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
        if (!usesHub(episode)) return fallback
        var s = status(episode.uuid) ?: return fallback
        var compress = hubCompresses(episode, s)
        // a user-started download jumps the hub's queue (also bumps a waiting Up Next prefetch); transcripts
        // without compression need the hub to have kept the original
        if (s.optString("state") in setOf("missing", "failed", "queued") || (!compress && !s.optBoolean("keep_source"))) {
            s = requestConversion(episode, original, priority = 0) ?: return fallback
            compress = hubCompresses(episode, s)
        }
        fun started(s: JSONObject) = if (compress) {
            s.optString("state") in setOf("encoding", "transcribing", "ready", "failed")
        } else {
            s.optBoolean("source_ready") || s.optString("state") == "failed"
        }
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (!started(s)) {
            if (System.currentTimeMillis() > deadline || isStopped()) return fallback
            Thread.sleep(2_000)
            s = status(episode.uuid) ?: return fallback
        }
        if (s.optString("state") == "failed") return fallback
        if (transcribes(episode)) attachTranscriptWhenReady(episode.uuid)
        if (!compress) {
            if (!sourceServable(episode.uuid)) return fallback // the publisher's file changed: no hub timings
            Timber.i("mimi-hub: downloading %s's original from the hub (%d bytes)", episode.uuid, s.optLong("source_bytes"))
            return episode.copy(downloadUrl = srcUrl(episode.uuid))
        }
        // EpisodeDownloader rejects podcast files under 150 KB; .mimi is 4.4 kb/s, so < ~4.5 min episodes
        if (s.optLong("mimi_bytes") < 160_000) return fallback
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
        if (!usesHub(episode) || isKnownReady(episode.uuid)) return
        val url = episode.downloadUrl?.takeIf { it.isNotBlank() } ?: return
        when (status(episode.uuid)?.optString("state")) {
            "ready" -> {
                markReady(episode.uuid)
                if (transcribes(episode)) attachTranscript(episode.uuid)
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
        if (usesHub(episode)) {
            var s = status(episode.uuid)
            if (s?.optString("state") in setOf("missing", "failed", "queued") ||
                (!hubCompresses(episode, s) && s?.optBoolean("keep_source") == false)
            ) {
                s = requestConversion(episode, original, priority = 0) // queued: bump a prefetch
            }
            val compress = hubCompresses(episode, s)
            fun ready(s: JSONObject?) = if (compress) s?.optString("state") in STREAMABLE else s?.optBoolean("source_ready") == true
            var waits = 0
            while (s != null && !ready(s) && s.optString("state") != "failed" && waits++ < 20) {
                delay(500)
                s = status(episode.uuid)
            }
            if (ready(s) && (compress || sourceServable(episode.uuid))) {
                if (transcribes(episode)) attachTranscriptWhenReady(episode.uuid)
                streamingHub.add(episode.uuid)
                return@withContext if (compress) {
                    StreamOverride(mimiUrl(episode.uuid), MIME_TYPE, isMimi = true)
                } else {
                    StreamOverride(srcUrl(episode.uuid), s?.optString("source_type")?.takeIf { it.isNotEmpty() }, isMimi = false)
                }
            }
        }
        streamingHub.remove(episode.uuid)
        cleanUrl(original).takeIf { it != original }?.let { StreamOverride(it, null, isMimi = false) }
    }

    private fun markReady(uuid: String) {
        prefs.edit().putBoolean(uuid, true).apply()
    }

    /** The hub transcript row for [uuid], if the hub is known to have it (and [podcastUuid], if given, wants it). */
    fun transcriptFor(uuid: String, podcastUuid: String? = null): Transcript? = if (
        isEnabled && isKnownReady(uuid) && (podcastUuid == null || transcribesPodcast(podcastUuid))
    ) {
        Transcript(episodeUuid = uuid, url = vttUrl(uuid), type = "text/vtt", isGenerated = false)
    } else {
        null
    }

    /** Adds the hub transcript to the episode's stored transcripts (keeping the existing ones). */
    suspend fun attachTranscript(uuid: String) {
        val ours = transcriptFor(uuid) ?: return
        val existing = transcriptDao.observeTranscripts(uuid).first()
        if (existing.none { it.url == ours.url }) transcriptDao.replaceAll(existing + ours)
        if (isSummariesEnabled) fetchSummaryAsync(uuid)
    }

fun summaryUrl(uuid: String) = "$base/v1/episodes/$uuid.summary.json"

    private val summaryDir by lazy { File(context.filesDir, "mimi_summaries").apply { mkdirs() } }
    private val summaryCache = ConcurrentHashMap<String, String>()
    private val summaryArrivedFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val noSummaryAnswers = ConcurrentHashMap<String, Int>()

    /** UUIDs of episodes whose summary was just fetched from the hub, so screens showing them can reload. */
    val summaryArrived: SharedFlow<String> = summaryArrivedFlow.asSharedFlow()

    fun getSummary(uuid: String): String? {
        if (!isSummariesEnabled) return null
        cachedSummary(uuid)?.let { return it }
        fetchSummaryAsync(uuid)
        return null
    }

    /** The summary if it's on the phone already; never asks the hub. */
    private fun cachedSummary(uuid: String): String? {
        summaryCache[uuid]?.let { return it }
        val file = File(summaryDir, "$uuid.html")
        if (!file.exists()) return null
        return file.readText().also { summaryCache[uuid] = it }
    }

    /**
     * For an episode that's playing with its ad marks known ([MimiSegments.onPlaying]): fetches the summary once
     * the hub has made it. The hub writes it right after the ad marks and says "pending" meanwhile. An episode it
     * won't summarise (failed, summaries off, or processed before they existed) is dropped: at once for "failed",
     * after two answers with no summary state otherwise (the hub sets "pending" just after saving the ad marks).
     */
    suspend fun checkSummary(uuid: String) {
        withContext(Dispatchers.IO) {
            if (!isSummariesEnabled || (noSummaryAnswers[uuid] ?: 0) >= 2 || cachedSummary(uuid) != null) return@withContext
            val s = status(uuid) ?: return@withContext // hub unreachable: the next check tries again
            when (s.optString("summary_state")) {
                "ready" -> fetchSummaryDirect(uuid)
                "pending" -> Unit
                "failed" -> noSummaryAnswers[uuid] = 2
                else -> noSummaryAnswers.merge(uuid, 1, Int::plus)
            }
        }
    }

    fun fetchSummaryAsync(uuid: String) {
        if (!isSummariesEnabled) return
        scope.launch {
            fetchSummaryDirect(uuid)
        }
    }

    suspend fun fetchSummaryDirect(uuid: String): String? = withContext(Dispatchers.IO) {
        if (!isSummariesEnabled) return@withContext null
        try {
            val req = Request.Builder().url(summaryUrl(uuid)).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val body = res.body.string()
                val json = JSONObject(body)
                val summary = json.optString("summary", "")
                if (summary.isNotEmpty()) {
                    summaryDir.mkdirs()
                    File(summaryDir, "$uuid.html").writeText(summary)
                    summaryCache[uuid] = summary
                    summaryArrivedFlow.tryEmit(uuid)
                    return@withContext summary
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch summary for $uuid")
        }
        null
    }

    fun formatSummaryCard(summaryHtml: String): String {
        return """
            <hr style="opacity: 0.2; margin-top: 20px;" />
            <div style="background-color: rgba(128,128,128,0.12); border-left: 4px solid #f43f5e; padding: 14px; margin-top: 20px; border-radius: 8px;">
                <h3 style="margin-top: 0; margin-bottom: 10px; color: #f43f5e;">✨ Episode Summary & Highlights</h3>
                $summaryHtml
            </div>
        """.trimIndent()
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
