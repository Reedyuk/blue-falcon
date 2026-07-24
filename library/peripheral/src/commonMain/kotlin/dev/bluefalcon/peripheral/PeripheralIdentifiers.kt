package dev.bluefalcon.peripheral

import dev.bluefalcon.core.Uuid
import kotlin.jvm.JvmInline

@JvmInline
value class PeripheralSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Peripheral session ID must not be blank" }
    }
}

@JvmInline
value class GattServiceId(val uuid: Uuid)

@JvmInline
value class GattCharacteristicId(val uuid: Uuid)

@JvmInline
value class GattDescriptorId(val uuid: Uuid)
