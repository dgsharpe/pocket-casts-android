package au.com.shiftyjelly.pocketcasts.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.CallOnce
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.components.FormFieldDialog
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRow
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRowToggle
import au.com.shiftyjelly.pocketcasts.compose.components.SettingSection
import au.com.shiftyjelly.pocketcasts.compose.components.SettingsSection
import au.com.shiftyjelly.pocketcasts.compose.components.TextP50
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.settings.viewmodel.AdvancedSettingsViewModel
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * The advanced settings page is for settings that we are not sure many users will need, or that we are
 * worried might create a worse user experience for most users. Basically, this is a place where we can
 * add settings without complicating things for the typical user.
 */
@Composable
fun AdvancedSettingsPage(
    viewModel: AdvancedSettingsViewModel,
    onBackPress: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val state: AdvancedSettingsViewModel.State by viewModel.state.collectAsState()
    AdvancedSettingsView(
        state = state,
        onBackPress = onBackPress,
        bottomInset = bottomInset,
        modifier = modifier,
    )

    CallOnce {
        viewModel.onShown()
    }
}

@Composable
fun AdvancedSettingsView(
    state: AdvancedSettingsViewModel.State,
    onBackPress: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        ThemedTopAppBar(
            title = stringResource(LR.string.settings_title_advanced),
            bottomShadow = true,
            onNavigationClick = { onBackPress() },
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomInset),
            modifier = Modifier
                .background(MaterialTheme.theme.colors.primaryUi02)
                .fillMaxHeight(),
        ) {
            item {
                TextP50(
                    text = stringResource(LR.string.settings_description_advanced),
                    color = MaterialTheme.theme.colors.primaryText02,
                    modifier = Modifier.padding(SettingsSection.horizontalPadding),
                )
            }
            item {
                SettingSection(
                    heading = stringResource(LR.string.settings_storage_section_heading_mobile_data),
                    indent = false,
                ) {
                    SyncOnMeteredRow(state.backgroundSyncOnMeteredState)
                }
            }
            item {
                SettingSection(
                    heading = stringResource(LR.string.settings_advanced_heading_playback),
                    indent = false,
                ) {
                    CacheEntirePlayingEpisodeRow(state.cacheEntirePlayingEpisodeState)
                    PrioritizeSeekAccuracydRow(state.prioritizeSeekAccuracyState)
                }
            }
            item {
                SettingSection(
                    heading = stringResource(LR.string.settings_advanced_heading_mimi),
                    indent = false,
                ) {
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_convert),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_convert_summary),
                        isChecked = state.mimiState.convertByDefaultChecked,
                        onCheckedChange = state.mimiState.onConvertByDefaultChange,
                    )
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_transcribe),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_transcribe_summary),
                        isChecked = state.mimiState.transcribeByDefaultChecked,
                        onCheckedChange = state.mimiState.onTranscribeByDefaultChange,
                    )
                    StripTrackingRow(state.mimiState)
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_skip_ads),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_skip_ads_summary),
                        isChecked = state.mimiState.skipAdsChecked,
                        onCheckedChange = state.mimiState.onSkipAdsChange,
                    )
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_skip_promos),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_skip_promos_summary),
                        isChecked = state.mimiState.skipPromosChecked,
                        onCheckedChange = state.mimiState.onSkipPromosChange,
                    )
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_trim_timeline),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_trim_timeline_summary),
                        isChecked = state.mimiState.trimTimelineChecked,
                        onCheckedChange = state.mimiState.onTrimTimelineChange,
                    )
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_download_on_stream),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_download_on_stream_summary),
                        isChecked = state.mimiState.downloadOnStreamChecked,
                        onCheckedChange = state.mimiState.onDownloadOnStreamChange,
                    )
                    MimiSwitchRow(
                        primaryText = stringResource(LR.string.settings_advanced_mimi_prefetch_wifi_only),
                        secondaryText = stringResource(LR.string.settings_advanced_mimi_prefetch_wifi_only_summary),
                        isChecked = state.mimiState.prefetchOnlyOnWifiChecked,
                        onCheckedChange = state.mimiState.onPrefetchOnlyOnWifiChange,
                    )
                    MimiHubUrlRow(state.mimiState)
                    MimiOpenRouterKeyRow(state.mimiState)
                }
            }
        }
    }
}

@Composable
private fun SyncOnMeteredRow(
    state: AdvancedSettingsViewModel.State.BackgroundSyncOnMeteredState,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_advanced_sync_on_metered),
        secondaryText = stringResource(LR.string.settings_advanced_sync_on_metered_summary),
        toggle = SettingRowToggle.Switch(state.isChecked, state.isEnabled),
        indent = false,
        modifier = modifier.toggleable(
            value = state.isChecked,
            role = Role.Switch,
            onValueChange = { state.onCheckedChange(it) },
        ),
    )
}

@Composable
private fun PrioritizeSeekAccuracydRow(
    state: AdvancedSettingsViewModel.State.PrioritizeSeekAccuracyState,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_advanced_prioritize_seek_accuracy),
        secondaryText = stringResource(LR.string.settings_advanced_prioritize_seek_accuracy_summary),
        toggle = SettingRowToggle.Switch(state.isChecked),
        indent = false,
        modifier = modifier.toggleable(
            value = state.isChecked,
            role = Role.Switch,
            onValueChange = { state.onCheckedChange(it) },
        ),
    )
}

@Composable
private fun CacheEntirePlayingEpisodeRow(
    state: AdvancedSettingsViewModel.State.CacheEntirePlayingEpisodeState,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_advanced_cache_entire_playing_episode),
        secondaryText = stringResource(LR.string.settings_advanced_cache_entire_playing_episode_summary),
        toggle = SettingRowToggle.Switch(state.isChecked),
        indent = false,
        modifier = modifier.toggleable(
            value = state.isChecked,
            role = Role.Switch,
            onValueChange = { state.onCheckedChange(it) },
        ),
    )
}

@Composable
private fun StripTrackingRow(
    state: AdvancedSettingsViewModel.State.MimiState,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_advanced_strip_tracking),
        secondaryText = stringResource(LR.string.settings_advanced_strip_tracking_summary),
        toggle = SettingRowToggle.Switch(state.stripTrackingChecked),
        indent = false,
        modifier = modifier.toggleable(
            value = state.stripTrackingChecked,
            role = Role.Switch,
            onValueChange = { state.onStripTrackingChange(it) },
        ),
    )
}

@Composable
private fun MimiSwitchRow(
    primaryText: String,
    secondaryText: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        primaryText = primaryText,
        secondaryText = secondaryText,
        toggle = SettingRowToggle.Switch(isChecked),
        indent = false,
        modifier = modifier.toggleable(
            value = isChecked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

@Composable
private fun MimiHubUrlRow(
    state: AdvancedSettingsViewModel.State.MimiState,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val title = stringResource(LR.string.settings_advanced_mimi_hub_url)
    val off = stringResource(LR.string.settings_advanced_mimi_hub_url_off)
    SettingRow(
        primaryText = title,
        secondaryText = state.hubUrl.ifBlank { off },
        indent = false,
        modifier = modifier.clickable { showDialog = true },
    )
    if (showDialog) {
        FormFieldDialog(
            title = title,
            placeholder = "http://your-hub:8765",
            initialValue = state.hubUrl,
            keyboardType = KeyboardType.Uri,
            onConfirm = {
                state.onHubUrlChange(it)
                showDialog = false
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Composable
private fun MimiOpenRouterKeyRow(
    state: AdvancedSettingsViewModel.State.MimiState,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val title = stringResource(LR.string.settings_advanced_mimi_openrouter_key)
    val none = stringResource(LR.string.settings_advanced_mimi_openrouter_key_none)
    SettingRow(
        primaryText = title,
        secondaryText = state.openRouterKeyHint.ifBlank { none },
        indent = false,
        modifier = modifier.clickable { showDialog = true },
    )
    if (showDialog) {
        // opens blank, so the saved key is never shown; saving blank removes it
        FormFieldDialog(
            title = title,
            placeholder = "sk-or-…",
            initialValue = "",
            keyboardType = KeyboardType.Password,
            onConfirm = {
                state.onOpenRouterKeyChange(it)
                showDialog = false
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdvancedSettingsPreview(
    @PreviewParameter(ThemePreviewParameterProvider::class) themeType: Theme.ThemeType,
) {
    AppThemeWithBackground(themeType) {
        AdvancedSettingsView(
            state = AdvancedSettingsViewModel.State(
                backgroundSyncOnMeteredState = AdvancedSettingsViewModel.State.BackgroundSyncOnMeteredState(
                    isChecked = true,
                    isEnabled = true,
                    onCheckedChange = {},
                ),
                prioritizeSeekAccuracyState = AdvancedSettingsViewModel.State.PrioritizeSeekAccuracyState(
                    isChecked = true,
                    onCheckedChange = {},
                ),
                cacheEntirePlayingEpisodeState = AdvancedSettingsViewModel.State.CacheEntirePlayingEpisodeState(
                    isChecked = true,
                    onCheckedChange = {},
                ),
                mimiState = AdvancedSettingsViewModel.State.MimiState(
                    stripTrackingChecked = true,
                    onStripTrackingChange = {},
                    convertByDefaultChecked = true,
                    onConvertByDefaultChange = {},
                    transcribeByDefaultChecked = true,
                    onTranscribeByDefaultChange = {},
                    skipAdsChecked = true,
                    onSkipAdsChange = {},
                    skipPromosChecked = true,
                    onSkipPromosChange = {},
                    trimTimelineChecked = false,
                    onTrimTimelineChange = {},
                    downloadOnStreamChecked = true,
                    onDownloadOnStreamChange = {},
                    prefetchOnlyOnWifiChecked = true,
                    onPrefetchOnlyOnWifiChange = {},
                    hubUrl = "http://your-hub:8765",
                    onHubUrlChange = {},
                    openRouterKeyHint = "",
                    onOpenRouterKeyChange = {},
                ),
            ),
            onBackPress = {},
            bottomInset = 0.dp,
        )
    }
}
