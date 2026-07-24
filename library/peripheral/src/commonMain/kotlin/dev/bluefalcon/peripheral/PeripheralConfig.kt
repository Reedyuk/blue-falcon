package dev.bluefalcon.peripheral

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class PeripheralConfig(
    val advertiseConfig: AdvertiseConfig,
    val responseDeadline: Duration = 30.seconds,
    val inactiveSessionTimeout: Duration = 5.minutes,
    val restorationIdentifier: String? = null,
) {
    init {
        require(responseDeadline > Duration.ZERO && responseDeadline.isFinite()) {
            "Response deadline must be positive and finite"
        }
        require(inactiveSessionTimeout > Duration.ZERO && inactiveSessionTimeout.isFinite()) {
            "Inactive session timeout must be positive and finite"
        }
        require(restorationIdentifier == null || restorationIdentifier.isNotBlank()) {
            "Restoration identifier must not be blank"
        }
    }
}

internal fun PeripheralConfig.copyForBackend(): PeripheralConfig = copy(
    advertiseConfig = advertiseConfig.copy(
        serviceUuids = advertiseConfig.serviceUuids.toList(),
        manufacturerData = advertiseConfig.manufacturerData.mapValues { (_, value) ->
            value.copyOf()
        },
        services = advertiseConfig.services.map { service ->
            service.copy(
                characteristics = service.characteristics.map { characteristic ->
                    characteristic.copy(
                        properties = characteristic.properties.toSet(),
                        initialValue = characteristic.initialValue?.copyOf(),
                        descriptors = characteristic.descriptors.map { descriptor ->
                            descriptor.copy(initialValue = descriptor.initialValue?.copyOf())
                        },
                    )
                },
            )
        },
    ),
)
