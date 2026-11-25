package dev.bluefalcon.core

import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import dev.bluefalcon.core.plugin.BlueFalconClient
import dev.bluefalcon.core.plugin.BlueFalconPlugin
import dev.bluefalcon.core.plugin.PluginConfig
import dev.bluefalcon.core.plugin.ScanCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScanConfigTest {

    @Test
    fun defaultsAreThePreviousHardcodedSettings() {
        val config = ScanConfig()

        assertEquals(ScanMode.LowLatency, config.mode)
        assertEquals(Duration.ZERO, config.reportDelay)
        assertTrue(config.allowDuplicates)
    }

    @Test
    fun negativeOrInfiniteReportDelayIsRejected() {
        assertFailsWith<IllegalArgumentException> { ScanConfig(reportDelay = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { ScanConfig(reportDelay = Duration.INFINITE) }
    }

    @Test
    fun defaultConfigUsesTheOriginalEngineEntryPoint() = runTest {
        val engine = FakeBlueFalconEngine()

        BlueFalcon(engine).scan()
        BlueFalcon(engine).scan(config = ScanConfig())

        assertNull(engine.lastScanConfig)
        assertTrue(engine.scanCalled)
    }

    @Test
    fun wrapperOverridingOnlyLegacyScanStillSeesDefaultScans() = runTest {
        var wrapped = 0
        val inner = FakeBlueFalconEngine()
        val wrapper = object : BlueFalconEngine by inner {
            override suspend fun scan(filters: List<ServiceFilter>) { wrapped++ }
        }

        BlueFalcon(wrapper).scan()

        assertEquals(1, wrapped)
        assertFalse(inner.scanCalled)
    }

    @Test
    fun scanConfigReachesEngineThroughPlugins() = runTest {
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        val seen = mutableListOf<ScanCall>()
        blueFalcon.plugins.install(object : BlueFalconPlugin {
            override fun install(client: BlueFalconClient, config: PluginConfig) {}
            override suspend fun onBeforeScan(call: ScanCall): ScanCall {
                seen += call
                return call.copy(config = call.config.copy(reportDelay = 5.seconds))
            }
        })
        val requested = ScanConfig(mode = ScanMode.LowPower, allowDuplicates = false)

        blueFalcon.scan(config = requested)

        assertEquals(requested, seen.single().config)
        assertEquals(requested.copy(reportDelay = 5.seconds), engine.lastScanConfig)
    }

    @Test
    fun engineDefaultImplementationIgnoresConfig() = runTest {
        var plainScans = 0
        val engine = object : BlueFalconEngine by FakeBlueFalconEngine() {
            override suspend fun scan(filters: List<ServiceFilter>) { plainScans++ }
            override suspend fun scan(filters: List<ServiceFilter>, config: ScanConfig) =
                super<BlueFalconEngine>.scan(filters, config)
        }

        engine.scan(emptyList(), ScanConfig(mode = ScanMode.LowPower))

        assertEquals(1, plainScans)
    }
}
