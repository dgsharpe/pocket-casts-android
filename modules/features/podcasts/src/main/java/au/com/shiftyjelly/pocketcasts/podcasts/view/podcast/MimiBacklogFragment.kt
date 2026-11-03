package au.com.shiftyjelly.pocketcasts.podcasts.view.podcast

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowButton
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowTextButton
import au.com.shiftyjelly.pocketcasts.compose.components.FormField
import au.com.shiftyjelly.pocketcasts.compose.components.TextH30
import au.com.shiftyjelly.pocketcasts.compose.components.TextP40
import au.com.shiftyjelly.pocketcasts.compose.components.TextP50
import au.com.shiftyjelly.pocketcasts.podcasts.viewmodel.PodcastSettingsViewModel
import au.com.shiftyjelly.pocketcasts.repositories.mimi.MimiHub
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseDialogFragment
import java.util.Locale
import kotlinx.coroutines.delay
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Convert episodes on Mimi server: all of this podcast's episodes, the latest N, or those published in a date range,
 * at once (the hub's /v1/backlog: big ones in bulk on Modal). Shows what it would do first (episodes, hours, where,
 * about how long and how much), and a running job's progress with a Stop button.
 */
internal class MimiBacklogFragment : BaseDialogFragment() {
    private val viewModel by viewModels<PodcastSettingsViewModel>({ requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = content {
        DialogBox(
            fillMaxHeight = false,
            modifier = Modifier.nestedScroll(rememberNestedScrollInteropConnection()),
        ) {
            val uiState by viewModel.uiState.collectAsState()
            val job = uiState?.mimi?.backlog
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding()) {
                TextH30(text = stringResource(LR.string.podcast_settings_mimi_backlog))
                Spacer(modifier = Modifier.height(12.dp))
                if (job != null && job.active) {
                    TextP40(text = stringResource(LR.string.mimi_backlog_progress, job.converted, job.total, job.ads, job.summaries))
                    Spacer(modifier = Modifier.height(12.dp))
                    RowButton(text = stringResource(LR.string.mimi_backlog_stop), onClick = { viewModel.cancelMimiBacklog() })
                    RowTextButton(text = stringResource(LR.string.mimi_backlog_close), onClick = { dismiss() })
                } else {
                    Choose(lastJob = job)
                }
            }
        }
    }

    @Composable
    private fun Choose(lastJob: MimiHub.BacklogJob?) {
        var choice by remember { mutableIntStateOf(0) } // all, the latest N, a date range
        var latest by remember { mutableStateOf("10") }
        var from by remember { mutableStateOf("") }
        var before by remember { mutableStateOf("") }
        val range = when (choice) {
            1 -> MimiHub.BacklogRange(latest = latest.toIntOrNull()?.takeIf { it > 0 })
            2 -> MimiHub.BacklogRange(after = from.trim().ifBlank { null }, before = before.trim().ifBlank { null })
            else -> MimiHub.BacklogRange()
        }
        var plan by remember { mutableStateOf<MimiHub.BacklogPlan?>(null) }
        var checking by remember { mutableStateOf(true) }
        LaunchedEffect(range) {
            checking = true
            delay(500) // (while typing)
            plan = viewModel.previewMimiBacklog(range)
            checking = false
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (lastJob != null) {
                TextP50(text = mimiBacklogLabel(lastJob))
                Spacer(modifier = Modifier.height(8.dp))
            }
            Choice(stringResource(LR.string.mimi_backlog_all), selected = choice == 0) { choice = 0 }
            Choice(stringResource(LR.string.mimi_backlog_latest), selected = choice == 1) { choice = 1 }
            if (choice == 1) {
                FormField(
                    value = latest,
                    placeholder = stringResource(LR.string.mimi_backlog_latest_count),
                    onValueChange = { latest = it.filter(Char::isDigit).take(5) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                )
            }
            Choice(stringResource(LR.string.mimi_backlog_range), selected = choice == 2) { choice = 2 }
            if (choice == 2) {
                FormField(
                    value = from,
                    placeholder = stringResource(LR.string.mimi_backlog_from),
                    onValueChange = { from = it.take(10) },
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 8.dp),
                )
                FormField(
                    value = before,
                    placeholder = stringResource(LR.string.mimi_backlog_to),
                    onValueChange = { before = it.take(10) },
                    modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            val p = plan
            TextP40(
                text = when {
                    checking -> stringResource(LR.string.mimi_backlog_checking)

                    p == null -> stringResource(LR.string.mimi_backlog_unavailable)

                    else -> stringResource(LR.string.mimi_backlog_plan, p.episodes, p.new, String.format(Locale.US, "%.1f", p.hours)) + " " +
                        if (p.onModal) {
                            stringResource(LR.string.mimi_backlog_plan_modal, p.minutes ?: 0, String.format(Locale.US, "%.2f", p.usd))
                        } else {
                            stringResource(LR.string.mimi_backlog_plan_local)
                        }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            RowButton(
                text = stringResource(LR.string.mimi_backlog_convert),
                enabled = !checking && p != null && p.new > 0,
                onClick = {
                    viewModel.startMimiBacklog(range)
                    dismiss()
                },
            )
            RowTextButton(text = stringResource(LR.string.mimi_backlog_close), onClick = { dismiss() })
        }
    }

    @Composable
    private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            TextP40(text = text)
        }
    }

    companion object {
        fun newInstance() = MimiBacklogFragment()
    }
}
