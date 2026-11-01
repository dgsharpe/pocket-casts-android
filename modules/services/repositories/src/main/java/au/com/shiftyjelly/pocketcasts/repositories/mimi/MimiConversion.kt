package au.com.shiftyjelly.pocketcasts.repositories.mimi

/**
 * Whether a podcast's episodes go through mimi-hub (a compact `.mimi`, the hub's transcript and ad segments) or
 * play as the original files. [Default] follows Settings > Advanced > Mimi server > Compress episodes.
 */
enum class MimiConversion {
    Default,
    On,
    Off,
}
