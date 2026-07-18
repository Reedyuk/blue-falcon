package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendGattServerRequest
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.awaitCancellation

internal class FakeAndroidBluetoothStack : AndroidBluetoothStack {
    override var capabilities = AndroidStackCapabilities(
        localGattServer = true,
        connectableAdvertising = true,
    )
    val calls = mutableListOf<String>()
    val listeners = mutableListOf<AndroidBluetoothStackListener>()
    val responses = mutableListOf<AndroidGattResponse>()
    val notifications = mutableListOf<AndroidNotificationRequest>()
    val disconnectedSessions = mutableListOf<PeripheralSessionId>()

    var addServiceFailureAt: Int? = null
    var advertisingFailure: Throwable? = null
    var suspendAddService = false
    var suspendAdvertising = false
    var notificationResult: AndroidNotificationStartResult =
        AndroidNotificationStartResult.Accepted

    val listener: AndroidBluetoothStackListener?
        get() = listeners.lastOrNull()

    override suspend fun open(listener: AndroidBluetoothStackListener) {
        calls += "open"
        listeners += listener
    }

    override suspend fun addService(service: GattServiceConfig) {
        calls += "service:${service.uuid}"
        val serviceCallCount = calls.count { it.startsWith("service:") }
        if (serviceCallCount == addServiceFailureAt) {
            throw IllegalStateException("service failed")
        }
        if (suspendAddService) awaitCancellation()
    }

    override suspend fun startAdvertising(config: dev.bluefalcon.peripheral.AdvertiseConfig) {
        calls += "advertise"
        advertisingFailure?.let { throw it }
        if (suspendAdvertising) awaitCancellation()
    }

    override fun sendResponse(response: AndroidGattResponse): Boolean {
        responses += response
        return true
    }

    override fun notify(request: AndroidNotificationRequest): AndroidNotificationStartResult {
        notifications += request
        return notificationResult
    }

    override fun disconnect(sessionId: PeripheralSessionId): Boolean {
        disconnectedSessions += sessionId
        return true
    }

    override fun stopAdvertising() {
        calls += "stopAdvertising"
    }

    override fun closeGattServer() {
        calls += "closeGattServer"
    }

    fun emit(event: AndroidGattEvent) = requireNotNull(listener).onEvent(event)

    fun emitFrom(listenerIndex: Int, event: AndroidGattEvent) {
        listeners[listenerIndex].onEvent(event)
    }
}

internal class RecordingBackendSink : PeripheralBackendEventSink {
    val openedSessions = mutableListOf<Pair<PeripheralSessionId, Int?>>()
    val closedSessions = mutableListOf<Pair<PeripheralSessionId, Throwable?>>()
    val subscriptionChanges = mutableListOf<Pair<PeripheralSessionId, Set<GattCharacteristicId>>>()
    val maximumLengths = mutableListOf<Pair<PeripheralSessionId, Int?>>()
    val readiness = mutableListOf<NotificationReadiness>()
    val requests = mutableListOf<BackendGattServerRequest>()
    val platformFailures = mutableListOf<Throwable>()

    val callbackCount: Int
        get() = openedSessions.size +
            closedSessions.size +
            subscriptionChanges.size +
            maximumLengths.size +
            readiness.size +
            requests.size +
            platformFailures.size

    override fun onSessionOpened(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) {
        openedSessions += sessionId to maximumUpdateValueLength
    }

    override fun onSessionClosed(sessionId: PeripheralSessionId, cause: Throwable?) {
        closedSessions += sessionId to cause
    }

    override fun onSubscriptionsChanged(
        sessionId: PeripheralSessionId,
        subscriptions: Set<GattCharacteristicId>,
    ) {
        subscriptionChanges += sessionId to subscriptions.toSet()
    }

    override fun onMaximumUpdateValueLengthChanged(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) {
        maximumLengths += sessionId to maximumUpdateValueLength
    }

    override fun onNotificationReady(readiness: NotificationReadiness) {
        this.readiness += readiness
    }

    override fun onRequest(request: BackendGattServerRequest) {
        requests += request
    }

    override fun onPlatformFailure(cause: Throwable) {
        platformFailures += cause
    }
}
