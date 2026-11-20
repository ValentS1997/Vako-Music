package ru.rikko.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckerTest {
    @Test fun comparesNumericReleaseTags() {
        assertTrue(ReleaseChecker.isNewer("v0.1.0", "0.0.1"))
        assertTrue(ReleaseChecker.isNewer("v1.10.0", "1.9.9"))
        assertFalse(ReleaseChecker.isNewer("v0.0.1", "0.0.1"))
        assertFalse(ReleaseChecker.isNewer("v0.0.9", "0.1.0"))
        assertFalse(ReleaseChecker.isNewer("unknown", "0.0.1"))
    }
}
