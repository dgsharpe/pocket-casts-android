package au.com.shiftyjelly.pocketcasts.repositories.mimi

import kotlin.math.max
import kotlin.math.min

/**
 * An episode's ad / self-promotion [segments] as the player's seek bar shows them. [cuts] (a subset) are left out of
 * the timeline altogether when "Trim skipped segments" is on: the bar, its time labels and the skip buttons then work
 * in trimmed time, which this maps to and from the real position. The player itself always stays on the real
 * timeline, so saved positions, sync, transcripts, chapters and bookmarks are unaffected.
 */
data class MimiTimeline(
    val segments: List<MimiSegments.Segment>,
    val cuts: List<MimiSegments.Segment> = emptyList(),
) {
    /** Sorted, non-overlapping real-time ranges that are cut. */
    private val ranges: List<LongRange> by lazy {
        val out = mutableListOf<LongRange>()
        for (s in cuts.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.last) {
                out[out.lastIndex] = last.first..max(last.last, s.endMs)
            } else if (s.endMs > s.startMs) {
                out.add(s.startMs..s.endMs)
            }
        }
        out
    }

    val isTrimmed: Boolean get() = ranges.isNotEmpty()

    /** Real [realMs] on the trimmed timeline; a position inside a cut maps to where the cut was. */
    fun toTrimmed(realMs: Long): Long {
        var trimmed = realMs
        for (r in ranges) {
            if (realMs <= r.first) break
            trimmed -= min(realMs, r.last) - r.first
        }
        return trimmed
    }

    /** Trimmed [trimmedMs] as a real position; where a cut was maps to just after it. */
    fun toReal(trimmedMs: Long): Long {
        var real = trimmedMs
        for (r in ranges) {
            if (real < r.first) break
            real += r.last - r.first
        }
        return real
    }

    /** The segments still on the timeline, in timeline (trimmed, if any cuts) time, for drawing. */
    val shown: List<MimiSegments.Segment> by lazy {
        segments.filter { it !in cuts }.map { it.copy(startMs = toTrimmed(it.startMs), endMs = toTrimmed(it.endMs)) }
    }
}
