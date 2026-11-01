package au.com.shiftyjelly.pocketcasts.repositories.mimi

import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry

/**
 * These (sanitized) transcript entries with the text of each of [cuts] replaced by one line, [label], timed to the
 * segment, for transcripts on the `.mimi` timeline (mimi-hub's). A line is folded when it starts inside a cut. The
 * speaker labels inside a cut go with it; after a fold the current speaker is labelled again.
 */
fun List<TranscriptEntry>.foldMimiSegments(
    cuts: List<MimiSegments.Segment>,
    label: (MimiSegments.Segment) -> String,
): List<TranscriptEntry> {
    if (cuts.isEmpty()) return this
    val out = ArrayList<TranscriptEntry>(size)
    var speaker: String? = null
    var shownSpeaker: String? = null
    var lastFold: MimiSegments.Segment? = null
    for (entry in this) {
        when (entry) {
            is TranscriptEntry.Speaker -> speaker = entry.name

            is TranscriptEntry.Text -> {
                val start = entry.startTimeMs
                val cut = if (start < 0) null else cuts.firstOrNull { it.startMs <= start && start < it.endMs }
                if (cut != null) {
                    if (cut !== lastFold) {
                        out += TranscriptEntry.Text(label(cut), startTimeMs = cut.startMs, endTimeMs = cut.endMs)
                        lastFold = cut
                    }
                    shownSpeaker = null
                } else {
                    lastFold = null
                    if (speaker != null && speaker != shownSpeaker) {
                        out += TranscriptEntry.Speaker(speaker)
                        shownSpeaker = speaker
                    }
                    out += entry
                }
            }
        }
    }
    return out
}
