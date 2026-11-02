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

/** Default / On / Off for this podcast's compression, transcripts and ad marks, or ad skipping. */
internal class MimiConversionFragment : BaseDialogFragment() {
    enum class Target {
        Compression,
        Transcripts,
        SkipAds,
    }

    private val viewModel by viewModels<PodcastSettingsViewModel>({ requireParentFragment() })
    private val target: Target
        get() = arguments?.getString(ARG_TARGET)?.let { Target.valueOf(it) }
            ?: if (arguments?.getBoolean(ARG_TRANSCRIPTS) == true) Target.Transcripts else Target.Compression

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
            val titleId = when (target) {
                Target.Compression -> LR.string.podcast_settings_mimi_conversion
                Target.Transcripts -> LR.string.podcast_settings_mimi_transcripts
                Target.SkipAds -> LR.string.podcast_settings_mimi_skip_ads
            }
            val selectedOption = when (target) {
                Target.Compression -> mimi?.compression
                Target.Transcripts -> mimi?.transcription
                Target.SkipAds -> mimi?.skipAds
            }
            val defaultVal = when (target) {
                Target.Compression -> mimi?.compressesByDefault ?: true
                Target.Transcripts -> mimi?.transcribesByDefault ?: true
                Target.SkipAds -> mimi?.skipsAdsByDefault ?: true
            }
            SelectedOptionColumn(
                title = getString(titleId),
                options = MimiConversion.entries,
                selectedOption = selectedOption,
                optionLabel = { option ->
                    mimiConversionLabel(option, defaultVal)
                },
                onClickOption = { option ->
                    when (target) {
                        Target.Compression -> viewModel.changeMimiCompression(option)
                        Target.Transcripts -> viewModel.changeMimiTranscription(option)
                        Target.SkipAds -> viewModel.changeMimiSkipAds(option)
                    }
                    dismiss()
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    companion object {
        private const val ARG_TRANSCRIPTS = "transcripts"
        private const val ARG_TARGET = "target"

        fun newInstance(target: Target) = MimiConversionFragment().apply {
            arguments = Bundle().apply { putString(ARG_TARGET, target.name) }
        }

        fun newInstance(transcripts: Boolean) = MimiConversionFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_TRANSCRIPTS, transcripts) }
        }
    }
}
