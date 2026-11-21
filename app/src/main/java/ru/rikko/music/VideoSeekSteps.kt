package ru.rikko.music

/** Maps a horizontal drag to recognizable video samples without changing timebar seeking. */
internal object VideoSeekSteps {
    const val STEP_MS = 3_000L
    private const val DP_PER_STEP = 24f

    fun candidate(startMs: Long, distancePx: Float, density: Float, durationMs: Long): Long {
        if (durationMs <= 0L || density <= 0f || !distancePx.isFinite()) return startMs.coerceAtLeast(0L)
        val steps = (distancePx / (DP_PER_STEP * density)).toLong()
        return (startMs + steps * STEP_MS).coerceIn(0L, durationMs)
    }
}
