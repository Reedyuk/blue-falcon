package com.example.bluefalconcomposemultiplatform.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.plugins.queue.PeripheralQueue
import kotlin.uuid.ExperimentalUuidApi

data class PeripheralExampleRuntime(
    val manager: BlueFalconPeripheral,
    val queue: PeripheralQueue,
)

/**
 * Client Characteristic Configuration Descriptor (`0x2902`). Centrals write this
 * descriptor to enable/disable notifications or indications on a characteristic.
 *
 * Platform peripheral backends handle this descriptor differently:
 * - Android forwards the raw ATT descriptor write to the app as a
 *   [dev.bluefalcon.peripheral.GattDescriptorWriteRequest] and only registers the
 *   subscription (and lets `PeripheralSession.subscriptions` reflect it) once the app
 *   responds with [dev.bluefalcon.peripheral.GattResponseStatus.Success].
 * - Apple's CoreBluetooth-backed stack manages the CCCD transparently via
 *   `didSubscribeToCharacteristic`/`didUnsubscribeFromCharacteristic` and never
 *   forwards a descriptor write request to the app for it.
 *
 * Example request handlers must therefore accept writes to this descriptor (instead of
 * rejecting all descriptor writes as unsupported) so notification subscriptions work on
 * platforms that surface it explicitly.
 */
@OptIn(ExperimentalUuidApi::class)
object ClientCharacteristicConfigurationDescriptor {
    const val uuid = "00002902-0000-1000-8000-00805f9b34fb"
    val id: GattDescriptorId = GattDescriptorId(uuid.toUuid())
}

@OptIn(ExperimentalUuidApi::class)
object EchoGatt {
    const val serviceUuid = "84f7e120-63fd-4f79-8b08-5b9780a36a94"
    const val characteristicUuid = "84f7e121-63fd-4f79-8b08-5b9780a36a94"
    const val restorationIdentifier = "dev.bluefalcon.example.echo-peripheral"

    val serviceId: GattServiceId = GattServiceId(serviceUuid.toUuid())
    val characteristicId: GattCharacteristicId =
        GattCharacteristicId(characteristicUuid.toUuid())
}

/**
 * Standard Bluetooth SIG Heart Rate profile (service `0x180D`), expressed with the
 * 128-bit Bluetooth Base UUID form so the same identifiers work across platforms.
 *
 * - Heart Rate Measurement (`0x2A37`, NOTIFY): periodic BPM readings.
 * - Body Sensor Location (`0x2A38`, READ): fixed sensor placement value. Reading this
 *   characteristic requires an encrypted/bonded link; the controller responds with
 *   [dev.bluefalcon.peripheral.GattResponseStatus.InsufficientAuthentication] on the
 *   first read attempt from a session, which prompts the platform Bluetooth stack to
 *   initiate bonding before the read is retried.
 * - Heart Rate Control Point (`0x2A39`, WRITE): accepts the "Reset Energy Expended"
 *   command (value `0x01`).
 */
@OptIn(ExperimentalUuidApi::class)
object HeartRateGatt {
    const val serviceUuid = "0000180d-0000-1000-8000-00805f9b34fb"
    const val heartRateMeasurementUuid = "00002a37-0000-1000-8000-00805f9b34fb"
    const val bodySensorLocationUuid = "00002a38-0000-1000-8000-00805f9b34fb"
    const val heartRateControlPointUuid = "00002a39-0000-1000-8000-00805f9b34fb"
    const val restorationIdentifier = "dev.bluefalcon.example.heart-rate-peripheral"

    const val BODY_SENSOR_LOCATION_CHEST: Byte = 0x01
    const val CONTROL_POINT_RESET_ENERGY_EXPENDED: Byte = 0x01

    val serviceId: GattServiceId = GattServiceId(serviceUuid.toUuid())
    val heartRateMeasurementId: GattCharacteristicId =
        GattCharacteristicId(heartRateMeasurementUuid.toUuid())
    val bodySensorLocationId: GattCharacteristicId =
        GattCharacteristicId(bodySensorLocationUuid.toUuid())
    val heartRateControlPointId: GattCharacteristicId =
        GattCharacteristicId(heartRateControlPointUuid.toUuid())
}
