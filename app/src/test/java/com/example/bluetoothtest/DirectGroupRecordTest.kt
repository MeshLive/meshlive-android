package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectGroupRecordTest {
    private class MemoryStorage(var text: String? = null) : DirectGroupRecord.Storage {
        var deleted = 0
        override fun read(): String? = text
        override fun write(text: String) {
            this.text = text
        }

        override fun delete() {
            deleted++
            text = null
        }
    }

    @Test
    fun addContainsAndRemove() {
        val storage = MemoryStorage()
        val record = DirectGroupRecord(storage)
        record.add("DIRECT-aa-111111")
        record.add("DIRECT-bb-222222")
        assertTrue(record.contains("DIRECT-aa-111111"))
        assertEquals(listOf("DIRECT-aa-111111", "DIRECT-bb-222222"), record.names())
        record.remove("DIRECT-aa-111111")
        assertEquals(listOf("DIRECT-bb-222222"), record.names())
    }

    @Test
    fun theCapKeepsTheNewestNames() {
        val storage = MemoryStorage()
        val record = DirectGroupRecord(storage, maxNames = 8)
        repeat(10) { record.add("DIRECT-$it") }
        assertEquals((2..9).map { "DIRECT-$it" }, record.names())
    }

    @Test
    fun duplicatesCollapse() {
        val storage = MemoryStorage()
        val record = DirectGroupRecord(storage)
        record.add("DIRECT-aa-111111")
        record.add("DIRECT-aa-111111")
        assertEquals(listOf("DIRECT-aa-111111"), record.names())
    }

    @Test
    fun clearDeletesTheFile() {
        val storage = MemoryStorage()
        val record = DirectGroupRecord(storage)
        record.add("DIRECT-aa-111111")
        record.clear()
        assertTrue(record.names().isEmpty())
        assertEquals(1, storage.deleted)
    }

    @Test
    fun removingTheLastNameDeletesTheFile() {
        val storage = MemoryStorage()
        val record = DirectGroupRecord(storage)
        record.add("DIRECT-aa-111111")
        record.remove("DIRECT-aa-111111")
        assertEquals(1, storage.deleted)
        assertNull(storage.text)
        assertTrue(record.names().isEmpty())
    }

    @Test
    fun anUnreadableStorageReadsAsEmpty() {
        val record = DirectGroupRecord(MemoryStorage(null))
        assertTrue(record.names().isEmpty())
        assertFalse(record.contains("DIRECT-aa-111111"))
    }

    @Test
    fun aThrowingWriteLeavesNamesUnchanged() {
        val storage = object : DirectGroupRecord.Storage {
            var text: String? = "DIRECT-aa-111111"
            override fun read(): String? = text
            override fun write(text: String) {
                throw IllegalStateException("wipe")
            }

            override fun delete() = Unit
        }
        val record = DirectGroupRecord(storage)
        runCatching { record.add("DIRECT-bb-222222") }
        assertTrue(record.names() == listOf("DIRECT-aa-111111"))
    }
}
