package dev.bluefalcon.peripheral.android

import android.bluetooth.le.AdvertiseSettings
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.AdvertiseMode
import dev.bluefalcon.peripheral.AdvertiseTxPower
import dev.bluefalcon.peripheral.GattServiceConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAdvertiseSettingsTest {
    @Test
    fun defaultConfigKeepsBalancedModeAndMediumPower() {
        assertEquals(
            AndroidAdvertiseSettings(
                advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                txPowerLevel = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                connectable = false,
            ),
            AdvertiseConfig().toAndroidAdvertiseSettings(),
        )
    }

    @Test
    fun modesPowerLevelsAndConnectabilityMapToFrameworkValues() {
        mapOf(
            AdvertiseMode.LowPower to AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            AdvertiseMode.Balanced to AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseMode.LowLatency to AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
        ).forEach { (mode, expected) ->
            assertEquals(expected, AdvertiseConfig(advertiseMode = mode).toAndroidAdvertiseSettings().advertiseMode)
        }
        mapOf(
            AdvertiseTxPower.UltraLow to AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
            AdvertiseTxPower.Low to AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            AdvertiseTxPower.Medium to AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            AdvertiseTxPower.High to AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
        ).forEach { (power, expected) ->
            assertEquals(expected, AdvertiseConfig(txPowerLevel = power).toAndroidAdvertiseSettings().txPowerLevel)
        }
        val connectable = AdvertiseConfig(services = listOf(GattServiceConfig("180d", emptyList())))
        assertEquals(true, connectable.toAndroidAdvertiseSettings().connectable)
    }
}
