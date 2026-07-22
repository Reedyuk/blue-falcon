package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattDescriptorConfig
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.NotificationMode
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreBluetooth.CBATTErrorInsufficientAuthentication
import platform.CoreBluetooth.CBATTErrorInsufficientAuthorization
import platform.CoreBluetooth.CBATTErrorInsufficientEncryption
import platform.CoreBluetooth.CBATTErrorInvalidAttributeValueLength
import platform.CoreBluetooth.CBATTErrorInvalidHandle
import platform.CoreBluetooth.CBATTErrorInvalidOffset
import platform.CoreBluetooth.CBATTErrorPrepareQueueFull
import platform.CoreBluetooth.CBATTErrorReadNotPermitted
import platform.CoreBluetooth.CBATTErrorRequestNotSupported
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTErrorUnlikelyError
import platform.CoreBluetooth.CBATTErrorWriteNotPermitted
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableDescriptor
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FrameworkAppleGattMappingsTest {

    @Test
    fun everyGattStatusMapsToClosestCoreBluetoothError() {
        val expected = mapOf(
            GattResponseStatus.Success to CBATTErrorSuccess,
            GattResponseStatus.InvalidHandle to CBATTErrorInvalidHandle,
            GattResponseStatus.ReadNotPermitted to CBATTErrorReadNotPermitted,
            GattResponseStatus.WriteNotPermitted to CBATTErrorWriteNotPermitted,
            GattResponseStatus.InvalidOffset to CBATTErrorInvalidOffset,
            GattResponseStatus.InvalidAttributeValueLength to
                CBATTErrorInvalidAttributeValueLength,
            GattResponseStatus.InsufficientAuthentication to
                CBATTErrorInsufficientAuthentication,
            GattResponseStatus.InsufficientAuthorization to
                CBATTErrorInsufficientAuthorization,
            GattResponseStatus.InsufficientEncryption to CBATTErrorInsufficientEncryption,
            GattResponseStatus.RequestNotSupported to CBATTErrorRequestNotSupported,
            GattResponseStatus.PrepareQueueFull to CBATTErrorPrepareQueueFull,
            GattResponseStatus.UnlikelyError to CBATTErrorUnlikelyError,
        )

        assertEquals(GattResponseStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, error) ->
            assertEquals(error, status.toAppleAttError())
        }
    }

    @Test
    fun propertiesAndDerivedPermissionsPreserveRequestedOperations() {
        val properties = setOf(
            CharacteristicProperty.READ,
            CharacteristicProperty.WRITE,
            CharacteristicProperty.WRITE_NO_RESPONSE,
            CharacteristicProperty.NOTIFY,
            CharacteristicProperty.INDICATE,
        )

        assertEquals(
            CBCharacteristicPropertyRead or
                CBCharacteristicPropertyWrite or
                CBCharacteristicPropertyWriteWithoutResponse or
                CBCharacteristicPropertyNotify or
                CBCharacteristicPropertyIndicate,
            properties.toAppleProperties(),
        )
        assertEquals(
            CBAttributePermissionsReadable or CBAttributePermissionsWriteable,
            GattCharacteristicConfig("2a37", properties).toApplePermissions(),
        )
    }

    @Test
    fun explicitPermissionsOverrideDerivedPermissions() {
        val explicit = CBAttributePermissionsWriteable
        val config = GattCharacteristicConfig(
            uuid = "2a37",
            properties = setOf(CharacteristicProperty.READ),
            permissions = explicit.toInt(),
        )

        assertEquals(explicit, config.toApplePermissions())
    }

    @Test
    fun cccdFormsAreFiltered() {
        assertTrue(isAppleManagedCccd("2902"))
        assertTrue(isAppleManagedCccd("00002902-0000-1000-8000-00805F9B34FB"))
        assertTrue(isAppleManagedCccd("00002902-0000-1000-8000-00805f9b34fb"))
        assertFalse(isAppleManagedCccd("2901"))
    }

    @Test
    fun notificationModesFollowCharacteristicProperties() {
        assertEquals(
            setOf(NotificationMode.Notification, NotificationMode.Indication),
            setOf(
                CharacteristicProperty.NOTIFY,
                CharacteristicProperty.INDICATE,
            ).toAppleNotificationModes(),
        )
        assertTrue(setOf(CharacteristicProperty.READ).toAppleNotificationModes().isEmpty())
    }

    @Test
    fun advertisingUsesOnlySupportedCoreBluetoothKeys() {
        val data = AdvertiseConfig(
            localName = "blue-falcon",
            serviceUuids = listOf("180d"),
        ).toAppleAdvertisementData()

        assertEquals("blue-falcon", data[CBAdvertisementDataLocalNameKey])
        assertTrue(data.containsKey(CBAdvertisementDataServiceUUIDsKey))
        assertEquals(2, data.size)
        assertTrue(AdvertiseConfig().toAppleAdvertisementData().isEmpty())
    }

    @Test
    fun gattBuilderUsesDynamicValuesAndFiltersAppleManagedCccd() {
        val service = GattServiceConfig(
            uuid = "180d",
            characteristics = listOf(
                GattCharacteristicConfig(
                    uuid = "2a37",
                    properties = setOf(
                        CharacteristicProperty.READ,
                        CharacteristicProperty.NOTIFY,
                    ),
                    initialValue = byteArrayOf(9),
                    descriptors = listOf(
                        GattDescriptorConfig("2902"),
                        GattDescriptorConfig("d0611e78-bbb4-4591-a5f8-487910ae4366"),
                    ),
                ),
            ),
        ).toAppleMutableService()

        @Suppress("UNCHECKED_CAST")
        val characteristic =
            (service.characteristics as List<CBMutableCharacteristic>).single()
        assertNull(characteristic.value)
        @Suppress("UNCHECKED_CAST")
        val descriptors = characteristic.descriptors as List<CBMutableDescriptor>
        assertEquals(1, descriptors.size)
        assertEquals(
            "d0611e78-bbb4-4591-a5f8-487910ae4366",
            normalizeAppleUuid(descriptors.single().UUID.UUIDString),
        )
    }

    @Test
    fun typedStandardDescriptorIsRejectedBeforeCoreBluetoothCanCrash() {
        assertFailsWith<IllegalArgumentException> {
            GattServiceConfig(
                uuid = "180d",
                characteristics = listOf(
                    GattCharacteristicConfig(
                        uuid = "2a37",
                        properties = setOf(CharacteristicProperty.READ),
                        descriptors = listOf(GattDescriptorConfig("2900")),
                    ),
                ),
            ).toAppleMutableService()
        }
    }

    @Test
    fun userDescriptionUsesRequiredStringValue() {
        val service = GattServiceConfig(
            uuid = "180d",
            characteristics = listOf(
                GattCharacteristicConfig(
                    uuid = "2a37",
                    properties = setOf(CharacteristicProperty.READ),
                    descriptors = listOf(
                        GattDescriptorConfig("2901", initialValue = "heart rate".encodeToByteArray()),
                    ),
                ),
            ),
        ).toAppleMutableService()
        @Suppress("UNCHECKED_CAST")
        val characteristic =
            (service.characteristics as List<CBMutableCharacteristic>).single()
        @Suppress("UNCHECKED_CAST")
        val descriptor = (characteristic.descriptors as List<CBMutableDescriptor>).single()

        assertEquals("heart rate", descriptor.value)
    }
}
