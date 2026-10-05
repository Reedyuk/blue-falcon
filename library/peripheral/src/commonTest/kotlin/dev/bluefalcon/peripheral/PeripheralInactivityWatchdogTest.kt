package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.internal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralInactivityWatchdogTest {
    @Test fun refreshReusesWatchdogAndOnlyLatestTokenExpires() = runTest {
        val admission = PeripheralRequestAdmission(maximumItems = 1, maximumBytes = 0)
        val expirations = mutableListOf<Long>()
        val watch = requireNotNull(PeripheralInactivityWatchdog.start(this, 1.seconds, 1, admission, expirations::add))
        repeat(10_000) { watch.refresh(it.toLong() + 2) }
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf(10_001L), expirations)
        watch.cancel(); runCurrent()
    }
    @Test fun canceledWatchdogsKeepAdmissionUntilActualCompletionOnStalledDispatcher() = runTest {
        val admission = PeripheralRequestAdmission(maximumItems = 2, maximumBytes = 0)
        val expirations = mutableListOf<Long>()
        val a = requireNotNull(PeripheralInactivityWatchdog.start(this, 1.seconds, 1, admission, expirations::add))
        val b = requireNotNull(PeripheralInactivityWatchdog.start(this, 1.seconds, 2, admission, expirations::add))
        a.cancel(); b.cancel()
        assertNull(PeripheralInactivityWatchdog.start(this, 1.seconds, 3, admission, expirations::add))
        runCurrent()
        val replacement = requireNotNull(PeripheralInactivityWatchdog.start(this, 1.seconds, 4, admission, expirations::add))
        a.cancel(); a.refresh(5)
        advanceTimeBy(1001); runCurrent()
        assertEquals(listOf(4L), expirations)
        replacement.cancel(); runCurrent()
    }
}
