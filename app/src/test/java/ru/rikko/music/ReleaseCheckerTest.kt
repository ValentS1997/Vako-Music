package ru.rikko.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckerTest {
    @Test fun comparesNumericReleaseTags() {
        assertTrue(ReleaseChecker.isNewer("v0.1.0", "0.0.1"))
        assertTrue(ReleaseChecker.isNewer("v1.10.0", "1.9.9"))
        assertFalse(ReleaseChecker.isNewer("v0.0.1", "0.0.1"))
        assertFalse(ReleaseChecker.isNewer("v0.0.9", "0.1.0"))
        assertFalse(ReleaseChecker.isNewer("unknown", "0.0.1"))
        assertTrue(ReleaseChecker.isNewer("v0.0.3", "0.0.2"))
    }

    @Test fun acceptsReleaseFromRenamedRepository() {
        val release = ReleaseChecker.parseLatest("""{"tag_name":"v0.0.3","html_url":"https://github.com/ValentS1997/Vako-Player/releases/tag/v0.0.3"}""")
        assertEquals("v0.0.3", release.version)
        assertEquals("https://github.com/ValentS1997/Vako-Player/releases/tag/v0.0.3", release.pageUrl)
    }

    @Test fun rejectsReleaseFromAnotherRepository() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseChecker.parseLatest("""{"tag_name":"v0.0.3","html_url":"https://github.com/ValentS1997/Vako-Music/releases/tag/v0.0.3"}""")
        }
    }
}
