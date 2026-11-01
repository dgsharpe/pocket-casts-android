package au.com.shiftyjelly.pocketcasts.podcasts.view.podcast

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import au.com.shiftyjelly.pocketcasts.compose.components.SelectedOptionColumn
import au.com.shiftyjelly.pocketcasts.podcasts.viewmodel.PodcastSettingsViewModel
import au.com.shiftyjelly.pocketcasts.repositories.mimi.MimiConversion
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseDialogFragment
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/** Default / On / Off for this podcast's compression on the Mimi server, or (transcripts = true) its transcripts and ad marks. */
internal class MimiConversionFragment : BaseDialogFragment() {
    private val viewModel by viewModels<PodcastSettingsViewModel>({ requireParentFragment() })
    private val transcripts get() = requireArguments().getBoolean(ARG_TRANSCRIPTS)

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
            val mimi = uiState?.mimi
            SelectedOptionColumn(
                title = getString(
                    if (transcripts) LR.string.podcast_settings_mimi_transcripts else LR.string.podcast_settings_mimi_conversion,
                ),
                options = MimiConversion.entries,
                selectedOption = if (transcripts) mimi?.transcription else mimi?.compression,
                optionLabel = { option ->
                    mimiConversionLabel(
                        option,
                        if (transcripts) mimi?.transcribesByDefault ?: true else mimi?.compressesByDefault ?: true,
                    )
                },
                onClickOption = { option ->
                    if (transcripts) viewModel.changeMimiTranscription(option) else viewModel.changeMimiCompression(option)
                    dismiss()
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    companion object {
        private const val ARG_TRANSCRIPTS = "transcripts"

        fun newInstance(transcripts: Boolean) = MimiConversionFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_TRANSCRIPTS, transcripts) }
        }
    }
}
