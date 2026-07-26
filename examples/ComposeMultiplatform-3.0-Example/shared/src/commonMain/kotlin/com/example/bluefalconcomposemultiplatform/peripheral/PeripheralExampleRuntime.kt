package com.example.bluefalconcomposemultiplatform.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.plugins.queue.PeripheralQueue
import kotlin.uuid.ExperimentalUuidApi

data class PeripheralExampleRuntime(
    val manager: BlueFalconPeripheral,
    val queue: PeripheralQueue,
)

@OptIn(ExperimentalUuidApi::class)
object EchoGatt {
    const val serviceUuid = "84f7e120-63fd-4f79-8b08-5b9780a36a94"
    const val characteristicUuid = "84f7e121-63fd-4f79-8b08-5b9780a36a94"
    const val restorationIdentifier = "dev.bluefalcon.example.echo-peripheral"

    val serviceId: GattServiceId = GattServiceId(serviceUuid.toUuid())
    val characteristicId: GattCharacteristicId =
        GattCharacteristicId(characteristicUuid.toUuid())
}
