@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.bluefalcon.engine.js

import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString

internal actual typealias WebBluetoothApi = WasmWebBluetoothApi
internal actual typealias WebDevice = WasmWebDevice
internal actual typealias WebService = WasmWebService
internal actual typealias WebCharacteristic = WasmWebCharacteristic

// ---- Web Bluetooth external declarations (Kotlin/Wasm) ----

internal external interface NavigatorBluetooth : JsAny {
    fun requestDevice(options: JsAny?): Promise<BluetoothDeviceJs>
}

internal external interface BluetoothDeviceJs : JsAny {
    val id: String
    val name: String?
    val gatt: GattServerJs?
}

internal external interface GattServerJs : JsAny {
    val connected: Boolean
    fun connect(): Promise<GattServerJs>
    fun disconnect()
    fun getPrimaryServices(uuid: JsAny?): Promise<JsArray<GattServiceJs>>
}

internal external interface GattServiceJs : JsAny {
    val uuid: String
    fun getCharacteristics(uuid: JsAny?): Promise<JsArray<GattCharacteristicJs>>
}

internal external interface GattCharacteristicJs : JsAny {
    val uuid: String
    val value: DataView?
    fun readValue(): Promise<DataView>
    fun writeValue(value: JsAny?): Promise<JsAny?>
    fun startNotifications(): Promise<GattCharacteristicJs>
    fun stopNotifications(): Promise<GattCharacteristicJs>
}

private fun navigatorBluetooth(): NavigatorBluetooth? =
    js("(typeof navigator !== 'undefined' && navigator.bluetooth) ? navigator.bluetooth : null")

private fun acceptAllOptions(optionalServices: JsArray<JsString>): JsAny =
    js("({ acceptAllDevices: true, optionalServices: optionalServices })")

// Web Bluetooth rejects requestDevice() with a NotFoundError when the user dismisses
// the chooser (or nothing matches). Swallow only that case — resolving to null — so a
// dismissal reads as "nothing selected"; every other rejection still propagates.
private fun requestDeviceOrNull(bluetooth: NavigatorBluetooth, options: JsAny): Promise<BluetoothDeviceJs?> =
    js("bluetooth.requestDevice(options).catch((e) => { if (e && e.name === 'NotFoundError') return null; throw e; })")

// Attaches a 'characteristicvaluechanged' listener and returns the JS callback so it
// can be removed later. The handler reads the characteristic's value on the JS side.
private fun addValueChangedListener(target: GattCharacteristicJs, handler: () -> Unit): JsAny =
    js("{ const cb = () => handler(); target.addEventListener('characteristicvaluechanged', cb); return cb; }")

private fun removeValueChangedListener(target: GattCharacteristicJs, callback: JsAny): Unit =
    js("target.removeEventListener('characteristicvaluechanged', callback)")

// "All services/characteristics" must call the method with NO argument. Passing an
// explicit null makes Web Bluetooth read it as a (invalid) service/characteristic name.
private fun getAllPrimaryServices(gatt: GattServerJs): Promise<JsArray<GattServiceJs>> =
    js("gatt.getPrimaryServices()")

private fun getAllCharacteristics(service: GattServiceJs): Promise<JsArray<GattCharacteristicJs>> =
    js("service.getCharacteristics()")

// A requested service/characteristic the device doesn't expose rejects with a
// NotFoundError; resolve to an empty array so a single missing UUID doesn't discard the
// services/characteristics that were found. Other rejections still propagate.
private fun getPrimaryServicesOrEmpty(gatt: GattServerJs, uuid: JsString): Promise<JsArray<GattServiceJs>> =
    js("gatt.getPrimaryServices(uuid).catch((e) => { if (e && e.name === 'NotFoundError') return []; throw e; })")

private fun getCharacteristicsOrEmpty(service: GattServiceJs, uuid: JsString): Promise<JsArray<GattCharacteristicJs>> =
    js("service.getCharacteristics(uuid).catch((e) => { if (e && e.name === 'NotFoundError') return []; throw e; })")

// ---- actual implementations ----

internal class WasmWebBluetoothApi {
    suspend fun requestDevice(optionalServiceUuids: List<String>): WasmWebDevice? {
        val bluetooth = navigatorBluetooth()
            ?: throw IllegalStateException("Web Bluetooth is not available in this browser")
        val options = acceptAllOptions(optionalServiceUuids.toJsStringArray())
        val device = requestDeviceOrNull(bluetooth, options).await() ?: return null
        return WasmWebDevice(device)
    }
}

internal class WasmWebDevice(val device: BluetoothDeviceJs) {
    val id: String get() = device.id
    val name: String? get() = device.name
    val isConnected: Boolean get() = device.gatt?.connected == true

    suspend fun connect() {
        device.gatt?.connect()?.await()
    }

    fun disconnect() {
        device.gatt?.disconnect()
    }

    suspend fun getPrimaryServices(serviceUuids: List<String>): List<WasmWebService> {
        val gatt = device.gatt ?: return emptyList()
        val services = if (serviceUuids.isEmpty()) {
            getAllPrimaryServices(gatt).await().toKotlinList()
        } else {
            serviceUuids.flatMap { getPrimaryServicesOrEmpty(gatt, it.toJsString()).await().toKotlinList() }
        }
        return services.map { WasmWebService(it) }
    }
}

internal class WasmWebService(val service: GattServiceJs) {
    val uuid: String get() = service.uuid

    suspend fun getCharacteristics(characteristicUuids: List<String>): List<WasmWebCharacteristic> {
        val characteristics = if (characteristicUuids.isEmpty()) {
            getAllCharacteristics(service).await().toKotlinList()
        } else {
            characteristicUuids.flatMap { getCharacteristicsOrEmpty(service, it.toJsString()).await().toKotlinList() }
        }
        return characteristics.map { WasmWebCharacteristic(it) }
    }
}

internal class WasmWebCharacteristic(val characteristic: GattCharacteristicJs) {
    // The JS callback returned by addEventListener, retained so it can be removed.
    private var valueChangedCallback: JsAny? = null

    val uuid: String get() = characteristic.uuid
    val value: ByteArray? get() = characteristic.value?.toByteArray()

    suspend fun readValue(): ByteArray = characteristic.readValue().await().toByteArray()

    suspend fun writeValue(value: ByteArray) {
        characteristic.writeValue(value.toDataView()).await()
    }

    suspend fun startNotifications(onValueChanged: (ByteArray) -> Unit) {
        // Replace any previous listener so repeated enables don't stack handlers.
        valueChangedCallback?.let { removeValueChangedListener(characteristic, it) }
        val callback = addValueChangedListener(characteristic) {
            characteristic.value?.toByteArray()?.let(onValueChanged)
        }
        valueChangedCallback = callback
        try {
            characteristic.startNotifications().await()
        } catch (error: Throwable) {
            // The native enable rejected (device disconnected, notifications unsupported,
            // …) — detach the callback we just attached so it and its closure don't leak,
            // then surface the failure.
            removeValueChangedListener(characteristic, callback)
            valueChangedCallback = null
            throw error
        }
    }

    suspend fun stopNotifications() {
        // Always detach the listener, even if the native stop rejects (e.g. the device
        // disconnected) — otherwise the JS callback and its closure leak.
        try {
            characteristic.stopNotifications().await()
        } finally {
            valueChangedCallback?.let {
                removeValueChangedListener(characteristic, it)
                valueChangedCallback = null
            }
        }
    }
}

// ---- interop helpers ----

private fun List<String>.toJsStringArray(): JsArray<JsString> {
    val array = JsArray<JsString>()
    forEachIndexed { index, value -> array[index] = value.toJsString() }
    return array
}

private fun <T : JsAny> JsArray<T>.toKotlinList(): List<T> {
    val result = ArrayList<T>(length)
    for (i in 0 until length) {
        get(i)?.let { result.add(it) }
    }
    return result
}

private fun DataView.toByteArray(): ByteArray {
    val result = ByteArray(byteLength)
    for (i in 0 until byteLength) {
        result[i] = getInt8(i)
    }
    return result
}

private fun ByteArray.toDataView(): DataView {
    val view = DataView(ArrayBuffer(size))
    for (i in indices) {
        view.setInt8(i, this[i])
    }
    return view
}
