package dev.koggles.presets

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderPickerTest {
    @Test fun acquireThrows_noRefresh_errorPublished_noCrash() = runTest {
        var refreshed = 0
        val errors = mutableListOf<String>()
        val picker = FolderPicker(
            scope = this,
            io = StandardTestDispatcher(testScheduler),
            acquire = { throw SecurityException("no persistable grant") },
            onAcquired = { refreshed++ },
            onFailed = { errors += it },
        )
        picker.picked("content://tree/x")
        advanceUntilIdle()
        assertEquals(0, refreshed)
        assertEquals(1, errors.size)
        assertTrue(errors.single(), errors.single().startsWith("Couldn't use that folder"))
    }

    @Test fun acquireRunsOnIoDispatcher() = runTest {
        val io = StandardTestDispatcher(testScheduler, name = "io")
        var ranOn: CoroutineDispatcher? = null
        var refreshed = 0
        val picker = FolderPicker(
            scope = this,
            io = io,
            acquire = { ranOn = kotlin.coroutines.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher },
            onAcquired = { refreshed++ },
            onFailed = {},
        )
        picker.picked("content://tree/x")
        advanceUntilIdle()
        assertSame(io, ranOn)
        assertEquals(1, refreshed)
    }

    @Test fun overlappingPicks_serializedAndLatestWins() = runTest {
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val acquired = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        var refreshed = 0
        val picker = FolderPicker(
            scope = this,
            io = StandardTestDispatcher(testScheduler),
            acquire = { uri ->
                active++; maxActive = maxOf(maxActive, active)
                if (uri == "first") firstGate.await()
                acquired += uri
                active--
            },
            onAcquired = { refreshed++ },
            onFailed = {},
        )
        picker.picked("first")
        testScheduler.runCurrent()
        picker.picked("second")
        testScheduler.runCurrent()
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("never two replacements at once", 1, maxActive)
        assertEquals("second", acquired.last())
        assertEquals("only the latest selection refreshes", 1, refreshed)
    }

    @Test fun successThenFailedLaterPick_stillRefreshes() = runTest {
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var refreshed = 0
        val errors = mutableListOf<String>()
        val picker = FolderPicker(
            scope = this,
            io = StandardTestDispatcher(testScheduler),
            acquire = { uri ->
                if (uri == "first") firstGate.await() else throw SecurityException("denied")
            },
            onAcquired = { refreshed++ },
            onFailed = { errors += it },
        )
        picker.picked("first")
        testScheduler.runCurrent()
        picker.picked("second")
        testScheduler.runCurrent()
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("first's grant replaced the old one: the catalog must follow it", 1, refreshed)
        assertEquals(1, errors.size)
    }
}
