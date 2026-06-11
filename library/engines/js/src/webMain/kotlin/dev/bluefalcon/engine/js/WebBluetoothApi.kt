package dev.bluefalcon.engine.js

/**
 * Thin interop surface over the browser Web Bluetooth API.
 *
 * The high-level engine logic in [JsEngine] is written entirely against these
 * declarations so it can be shared between the `js` and `wasmJs` targets. Each
 * target provides an `actual` implementation that adapts its native JS interop
 * (Kotlin/JS `dynamic` + `kotlin.js.Promise` vs Kotlin/Wasm `JsAny` externals)
 * into plain Kotlin types.
 */
internal expect class WebBluetoothApi() {
    /**
     * Prompts the user to pick a device and returns the chosen [WebDevice] (Web
     * Bluetooth surfaces one device per request).
     *
     * Returns `null` when the user dismisses the chooser without selecting a device —
     * Web Bluetooth rejects both this and the "no matching device" case with a
     * `NotFoundError`, and neither is a failure here. Any other error (Web Bluetooth
     * unavailable, blocked by permissions policy, etc.) propagates to the caller.
     *
     * [optionalServiceUuids] are declared up-front as services the page may access —
     * Web Bluetooth blocks all GATT access otherwise. The chooser shows every nearby
     * device rather than filtering on advertised services, because many peripherals
     * expose a service in their GATT table without advertising its UUID.
     */
    suspend fun requestDevice(optionalServiceUuids: List<String>): WebDevice?
}

internal expect class WebDevice {
    val id: String
    val name: String?
    val isConnected: Boolean
    suspend fun connect()
    fun disconnect()
    suspend fun getPrimaryServices(serviceUuids: List<String>): List<WebService>
}

internal expect class WebService {
    val uuid: String
    suspend fun getCharacteristics(characteristicUuids: List<String>): List<WebCharacteristic>
}

internal expect class WebCharacteristic {
    val uuid: String
    val value: ByteArray?
    suspend fun readValue(): ByteArray
    suspend fun writeValue(value: ByteArray)

    /**
     * Enables notifications/indications and registers a `characteristicvaluechanged`
     * listener. [onValueChanged] is invoked with the decoded value each time the
     * peripheral pushes an update.
     */
    suspend fun startNotifications(onValueChanged: (ByteArray) -> Unit)
    suspend fun stopNotifications()
}