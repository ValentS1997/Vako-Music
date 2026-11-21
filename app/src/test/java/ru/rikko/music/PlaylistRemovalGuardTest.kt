package ru.rikko.music

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistRemovalGuardTest {
    private val displayed = listOf(Track("1", "10"), Track("2", "20"))

    @Test fun unchangedListKeepsTheSelectedOccurrence() {
        val raw = JSONArray("""[{"track":{"id":"1","albums":[{"id":"10"}]}},{"track":{"id":"2","albums":[{"id":"20"}]}}]""")
        assertEquals(1, PlaylistRemovalGuard.positionIfUnchanged(displayed, raw, 1))
    }

    @Test fun insertionAndReorderRejectOldPosition() {
        val inserted = JSONArray("""[{"track":{"id":"9","albums":[{"id":"90"}]}},{"track":{"id":"1","albums":[{"id":"10"}]}},{"track":{"id":"2","albums":[{"id":"20"}]}}]""")
        val reordered = JSONArray("""[{"track":{"id":"2","albums":[{"id":"20"}]}},{"track":{"id":"1","albums":[{"id":"10"}]}}]""")
        assertThrows(IllegalStateException::class.java) { PlaylistRemovalGuard.positionIfUnchanged(displayed, inserted, 1) }
        assertThrows(IllegalStateException::class.java) { PlaylistRemovalGuard.positionIfUnchanged(displayed, reordered, 1) }
    }

    @Test fun incompleteEntryRejectsInsteadOfShiftingIndices() {
        val raw = JSONArray("""[null,{"track":{"id":"2","albums":[{"id":"20"}]}}]""")
        assertThrows(IllegalStateException::class.java) { PlaylistRemovalGuard.positionIfUnchanged(displayed, raw, 1) }
    }

    @Test fun duplicateTracksArePositionedOnlyWhenWholeSnapshotMatches() {
        val duplicate = listOf(Track("1", "10"), Track("1", "10"))
        val raw = JSONArray("""[{"track":{"id":"1","albums":[{"id":"10"}]}},{"track":{"id":"1","albums":[{"id":"10"}]}}]""")
        assertEquals(1, PlaylistRemovalGuard.positionIfUnchanged(duplicate, raw, 1))
    }
}
