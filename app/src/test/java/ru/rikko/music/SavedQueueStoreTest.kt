package ru.rikko.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedQueueStoreTest {
    @Test fun mixedQueueRoundTripsWithoutLosingCurrentIndex() {
        val original = SavedQueueStore.Snapshot(
            listOf(
                SavedQueueStore.Entry(localUri = "content://media/external/audio/media/4"),
                SavedQueueStore.Entry(remote = Track("55", "10", "Название", "Исполнитель"))
            ), index = 1
        )
        val restored = SavedQueueStore.Codec.decode(SavedQueueStore.Codec.encode(original))
        assertEquals(1, restored?.index)
        assertEquals(original.entries.first().localUri, restored?.entries?.first()?.localUri)
        assertEquals("55", restored?.entries?.last()?.remote?.trackId)
    }

    @Test fun emptyQueueHasSafeIndexAndCorruptInputDoesNotCrash() {
        val empty = SavedQueueStore.Snapshot(emptyList(), index = 99)
        assertEquals(0, SavedQueueStore.Codec.decode(SavedQueueStore.Codec.encode(empty))?.index)
        assertNull(SavedQueueStore.Codec.decode("не очередь"))
    }
}
