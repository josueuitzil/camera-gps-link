package org.kutner.cameragpslink

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingLogFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun keepsOnlyTwoBoundedFilesWithNewestEvidence() {
        val file = File(temporary.root, "events.log")
        val store = RotatingLogFile(file, 128)
        repeat(100) { store.append("event-$it") }
        assertTrue(file.length() <= 128)
        assertTrue(File(temporary.root, "events.log.previous").length() <= 128)
        assertEquals(2, temporary.root.listFiles()!!.size)
        assertTrue(store.read().contains("event-99\n"))
        assertFalse(store.read().contains("event-0\n"))
    }

    @Test fun largeUnicodeExceptionCannotExceedFileBudget() {
        val file = File(temporary.root, "crash.log")
        val store = RotatingLogFile(file, 128)
        store.append("é界".repeat(10_000))
        assertTrue(file.length() <= 128)
        assertFalse(store.read().contains('\uFFFD'))
    }

    @Test fun concurrentCallbacksNeverInterleaveOrLoseRecordsBeforeRotation() {
        val store = RotatingLogFile(File(temporary.root, "events.log"))
        val callbacks = Executors.newFixedThreadPool(4)
        repeat(200) { value -> callbacks.submit { store.append("event-$value") } }
        callbacks.shutdown()
        assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS))
        val lines = store.read().lineSequence().filter(String::isNotBlank).toSet()
        assertEquals((0 until 200).map { "event-$it" }.toSet(), lines)
    }
}
