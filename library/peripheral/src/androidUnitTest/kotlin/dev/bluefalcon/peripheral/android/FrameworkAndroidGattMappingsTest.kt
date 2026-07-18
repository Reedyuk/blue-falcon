package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattDescriptorConfig
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.NotificationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class FrameworkAndroidGattMappingsTest {
    @Test
    fun responseStatusesMapToAndroidGattConstants() {
        val expected = mapOf(
            GattResponseStatus.Success to BluetoothGatt.GATT_SUCCESS,
            GattResponseStatus.InvalidHandle to 0x01,
            GattResponseStatus.ReadNotPermitted to BluetoothGatt.GATT_READ_NOT_PERMITTED,
            GattResponseStatus.WriteNotPermitted to BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
            GattResponseStatus.InvalidOffset to BluetoothGatt.GATT_INVALID_OFFSET,
            GattResponseStatus.InvalidAttributeValueLength to 0x0d,
            GattResponseStatus.InsufficientAuthentication to BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            GattResponseStatus.InsufficientAuthorization to 0x08,
            GattResponseStatus.InsufficientEncryption to 0x0f,
            GattResponseStatus.RequestNotSupported to 0x06,
            GattResponseStatus.PrepareQueueFull to 0x09,
            GattResponseStatus.UnlikelyError to BluetoothGatt.GATT_FAILURE,
        )

        assertEquals(GattResponseStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, androidStatus) ->
            assertEquals(androidStatus, status.toAndroidGattStatus(), status.name)
        }
    }

    @Test
    fun propertiesAndDefaultPermissionsMapWithoutLosingBits() {
        val properties = CharacteristicProperty.entries.toSet()

        assertEquals(
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE or
                BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE or
                BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS,
            properties.toAndroidProperties(),
        )
        assertEquals(
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            properties.toAndroidPermissions(),
        )
    }

    @Test
    fun explicitCharacteristicPermissionsOverrideDerivedPermissions() {
        val config = GattCharacteristicConfig(
            uuid = CHARACTERISTIC_UUID,
            properties = setOf(CharacteristicProperty.READ),
            permissions = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )

        assertEquals(
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            config.toAndroidPermissions(),
        )
    }

    @Test
    fun descriptorPermissionsAreDerivedUnlessExplicit() {
        val derived = GattDescriptorConfig(uuid = DESCRIPTOR_UUID)
        val explicit = GattDescriptorConfig(
            uuid = DESCRIPTOR_UUID,
            permissions = BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
        )

        assertEquals(
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            derived.toAndroidPermissions(),
        )
        assertEquals(
            BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
            explicit.toAndroidPermissions(),
        )
    }

    @Test
    fun uuidConversionPreservesTheValue() {
        val uuid = Uuid.parse(SERVICE_UUID)

        assertEquals(uuid, uuid.toJavaUuid().toKotlinUuid())
    }

    @Test
    fun indicationRequiresAndroidConfirmation() {
        assertFalse(NotificationMode.Notification.toAndroidConfirm())
        assertTrue(NotificationMode.Indication.toAndroidConfirm())
    }

    private companion object {
        const val SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
        const val CHARACTERISTIC_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
        const val DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
