package ru.rikko.music

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSeekStepsTest {
    @Test fun samplesThreeSecondStepsFromGestureStart() {
        val start = 15_000L
        assertEquals(start, VideoSeekSteps.candidate(start, 47f, 2f, 30_000))
        assertEquals(18_000, VideoSeekSteps.candidate(start, 48f, 2f, 30_000))
        assertEquals(21_000, VideoSeekSteps.candidate(start, 96f, 2f, 30_000))
        assertEquals(12_000, VideoSeekSteps.candidate(start, -48f, 2f, 30_000))
        assertEquals(9_000, VideoSeekSteps.candidate(start, -96f, 2f, 30_000))
    }

    @Test fun clampsToFirstAndLastFrameTime() {
        assertEquals(0, VideoSeekSteps.candidate(1_000, -200f, 2f, 30_000))
        assertEquals(30_000, VideoSeekSteps.candidate(29_000, 200f, 2f, 30_000))
    }

    @Test fun unknownDurationDoesNotSeek() {
        assertEquals(15_000, VideoSeekSteps.candidate(15_000, 200f, 2f, 0))
    }
}
