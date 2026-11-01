package au.com.shiftyjelly.pocketcasts.settings.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.refresh.RefreshPodcastsTask
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.SettingsAdvancedCacheEntirePlayingEpisodeEvent
import com.automattic.eventhorizon.SettingsAdvancedPrioritizeSeekAccuracyEvent
import com.automattic.eventhorizon.SettingsAdvancedShownEvent
import com.automattic.eventhorizon.SettingsAdvancedSyncOnMeteredEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val eventHorizon: EventHorizon,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initState())
    val state: StateFlow<State> = mutableState

    private fun initState() = State(
        backgroundSyncOnMeteredState = State.BackgroundSyncOnMeteredState(
            isChecked = settings.syncOnMeteredNetwork(),
            isEnabled = settings.backgroundRefreshPodcasts.value,
            onCheckedChange = {
                // isEnabled controls the grey out of the function but not if it's actually called
                // here we disable the functionality
                if (settings.backgroundRefreshPodcasts.value) {
                    onSyncOnMeteredCheckedChange(it)
                    eventHorizon.track(
                        SettingsAdvancedSyncOnMeteredEvent(
                            enabled = it,
                        ),
                    )
                }
            },
        ),
        prioritizeSeekAccuracyState = State.PrioritizeSeekAccuracyState(
            isChecked = settings.prioritizeSeekAccuracy.value,
            onCheckedChange = {
                settings.prioritizeSeekAccuracy.set(it, updateModifiedAt = false)
                updatePrioritizeSeekAccuracyState()
                eventHorizon.track(
                    SettingsAdvancedPrioritizeSeekAccuracyEvent(
                        enabled = it,
                    ),
                )
            },
        ),
        cacheEntirePlayingEpisodeState = State.CacheEntirePlayingEpisodeState(
            isChecked = settings.cacheEntirePlayingEpisode.value,
            onCheckedChange = {
                settings.cacheEntirePlayingEpisode.set(it, updateModifiedAt = false)
                updateCacheEntirePlayingEpisodeState()
                eventHorizon.track(
                    SettingsAdvancedCacheEntirePlayingEpisodeEvent(
                        enabled = it,
                    ),
                )
            },
        ),
        mimiState = mimiState(),
    )

    private fun mimiState() = State.MimiState(
        stripTrackingChecked = settings.stripTrackingRedirects.value,
        onStripTrackingChange = {
            settings.stripTrackingRedirects.set(it, updateModifiedAt = false)
            updateMimiState()
        },
        convertByDefaultChecked = settings.mimiConvertByDefault.value,
        onConvertByDefaultChange = {
            settings.mimiConvertByDefault.set(it, updateModifiedAt = false)
            updateMimiState()
        },
        skipAdsChecked = settings.mimiSkipAds.value,
        onSkipAdsChange = {
            settings.mimiSkipAds.set(it, updateModifiedAt = false)
            updateMimiState()
        },
        skipPromosChecked = settings.mimiSkipPromos.value,
        onSkipPromosChange = {
            settings.mimiSkipPromos.set(it, updateModifiedAt = false)
            updateMimiState()
        },
        trimTimelineChecked = settings.mimiTrimTimeline.value,
        onTrimTimelineChange = {
            settings.mimiTrimTimeline.set(it, updateModifiedAt = false)
            updateMimiState()
        },
        downloadOnStreamChecked = settings.mimiDownloadOnStream.value,
        onDownloadOnStreamChange = {
            settings.mimiDownloadOnStream.set(it, updateModifiedAt = false)
            updateMimiState()
        },
        hubUrl = settings.mimiHubUrl.value,
        onHubUrlChange = {
            settings.mimiHubUrl.set(it.trim(), updateModifiedAt = false)
            updateMimiState()
        },
    )

    private fun updateMimiState() {
        mutableState.value = mutableState.value.copy(mimiState = mimiState())
    }

    private fun onSyncOnMeteredCheckedChange(isChecked: Boolean) {
        settings.setSyncOnMeteredNetwork(isChecked)
        updateSyncOnMeteredState()

        // Update worker to take sync setting into account
        RefreshPodcastsTask.scheduleOrCancel(context, settings)
    }

    private fun updateSyncOnMeteredState() {
        mutableState.value = mutableState.value.copy(
            backgroundSyncOnMeteredState = mutableState.value.backgroundSyncOnMeteredState.copy(
                isChecked = settings.syncOnMeteredNetwork(),
            ),
        )
    }

    private fun updatePrioritizeSeekAccuracyState() {
        mutableState.value = mutableState.value.copy(
            prioritizeSeekAccuracyState = mutableState.value.prioritizeSeekAccuracyState.copy(
                isChecked = settings.prioritizeSeekAccuracy.value,
            ),
        )
    }

    private fun updateCacheEntirePlayingEpisodeState() {
        mutableState.value = mutableState.value.copy(
            cacheEntirePlayingEpisodeState = mutableState.value.cacheEntirePlayingEpisodeState.copy(
                isChecked = settings.cacheEntirePlayingEpisode.value,
            ),
        )
    }

    fun onShown() {
        eventHorizon.track(SettingsAdvancedShownEvent)
    }

    data class State(
        val backgroundSyncOnMeteredState: BackgroundSyncOnMeteredState,
        val prioritizeSeekAccuracyState: PrioritizeSeekAccuracyState,
        val cacheEntirePlayingEpisodeState: CacheEntirePlayingEpisodeState,
        val mimiState: MimiState,
    ) {

        data class MimiState(
            val stripTrackingChecked: Boolean,
            val onStripTrackingChange: (Boolean) -> Unit,
            val convertByDefaultChecked: Boolean,
            val onConvertByDefaultChange: (Boolean) -> Unit,
            val skipAdsChecked: Boolean,
            val onSkipAdsChange: (Boolean) -> Unit,
            val skipPromosChecked: Boolean,
            val onSkipPromosChange: (Boolean) -> Unit,
            val trimTimelineChecked: Boolean,
            val onTrimTimelineChange: (Boolean) -> Unit,
            val downloadOnStreamChecked: Boolean,
            val onDownloadOnStreamChange: (Boolean) -> Unit,
            val hubUrl: String,
            val onHubUrlChange: (String) -> Unit,
        )

        data class BackgroundSyncOnMeteredState(
            val isChecked: Boolean,
            val isEnabled: Boolean,
            val onCheckedChange: (Boolean) -> Unit,
        )

        data class PrioritizeSeekAccuracyState(
            val isChecked: Boolean,
            val onCheckedChange: (Boolean) -> Unit,
        )

        data class CacheEntirePlayingEpisodeState(
            val isChecked: Boolean,
            val onCheckedChange: (Boolean) -> Unit,
        )
    }
}
