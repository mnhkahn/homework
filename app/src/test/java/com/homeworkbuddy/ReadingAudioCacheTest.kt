package com.homeworkbuddy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingAudioCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cacheSurvivesRecreationAndSeparatesSentences() = runBlocking {
        val directory = temporary.newFolder()
        val first = ReadingAudioCache(directory).getOrCreate("en-US:first") { it.writeText("first"); ReadingAudioSource.SYSTEM }!!
        val cached = ReadingAudioCache(directory).getOrCreate("en-US:first") { error("must not regenerate audio") }!!
        assertEquals(first, cached)
        assertEquals("first", cached.file.readText())
        val other = ReadingAudioCache(directory).getOrCreate("en-US:second") { it.writeText("second"); ReadingAudioSource.ONLINE }!!
        assertNotEquals(first.file, other.file)
        assertEquals(ReadingAudioSource.SYSTEM, cached.source)
        assertEquals(ReadingAudioSource.ONLINE, other.source)
        val online = ReadingAudioCache(directory).getOrCreate("en-US:second") { error("must retain online source") }!!
        assertEquals(ReadingAudioSource.ONLINE, online.source)
    }

    @Test fun failedEmptyAndCancelledWritesAreNotPublished() = runBlocking {
        val directory = temporary.newFolder()
        val cache = ReadingAudioCache(directory)
        assertNull(cache.getOrCreate("failure") { it.writeText("partial"); null })
        assertNull(cache.getOrCreate("empty") { ReadingAudioSource.SYSTEM })
        try {
            cache.getOrCreate("cancelled") { it.writeText("partial"); throw kotlinx.coroutines.CancellationException() }
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNotNull(cache.getOrCreate("failure") { it.writeText("retry"); ReadingAudioSource.ONLINE })
    }

    @Test fun evictsOldestAudioWithinSizeBudget() = runBlocking {
        val directory = temporary.newFolder()
        val cache = ReadingAudioCache(directory, maxBytes = 8)
        val oldest = cache.getOrCreate("old") { it.writeText("1234"); ReadingAudioSource.SYSTEM }!!
        oldest.file.setLastModified(1L)
        val recent = cache.getOrCreate("recent") { it.writeText("5678"); ReadingAudioSource.ONLINE }!!
        val newest = cache.getOrCreate("new") { it.writeText("9012"); ReadingAudioSource.SYSTEM }!!
        assertFalse(oldest.file.exists())
        assertTrue(recent.file.exists())
        assertTrue(newest.file.exists())
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 8L)
    }
}
