package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendGattServerRequest
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink

internal class FakeApplePeripheralStack : ApplePeripheralStack {
    var openResult = AppleOpenResult(restored = false, advertising = true)
    var openFailure: Throwable? = null
    var notificationResult: AppleNotificationStartResult = AppleNotificationStartResult.Accepted
    var sendResponseResult = true
    val openConfigs = mutableListOf<PeripheralConfig>()
    val responses = mutableListOf<AppleGattResponse>()
    val notifications = mutableListOf<AppleNotificationRequest>()
    var stopAdvertisingCalls = 0
        private set
    var clearServicesCalls = 0
        private set
    var closeCalls = 0
        private set

    lateinit var listener: ApplePeripheralStackListener
        private set

    override suspend fun open(
        config: PeripheralConfig,
        listener: ApplePeripheralStackListener,
    ): AppleOpenResult {
        openConfigs += config
        this.listener = listener
        openFailure?.let { throw it }
        return openResult
    }

    override fun sendResponse(response: AppleGattResponse): Boolean {
        responses += AppleGattResponse(
            sessionId = response.sessionId,
            requestToken = response.requestToken,
            status = response.status,
            value = response.value,
        )
        return sendResponseResult
    }

    override fun notify(request: AppleNotificationRequest): AppleNotificationStartResult {
        notifications += AppleNotificationRequest(
            sessionId = request.sessionId,
            characteristicId = request.characteristicId,
            mode = request.mode,
            value = request.value,
        )
        return notificationResult
    }

    override fun stopAdvertising() {
        stopAdvertisingCalls++
    }

    override fun clearServices() {
        clearServicesCalls++
    }

    override fun close() {
        closeCalls++
    }

    fun emit(event: AppleGattEvent) {
        listener.onEvent(event)
    }

    fun fail(cause: Throwable) {
        listener.onPlatformFailure(cause)
    }
}

internal class RecordingAppleBackendSink : PeripheralBackendEventSink {
    val openedSessions = mutableListOf<Pair<PeripheralSessionId, Int?>>()
    val closedSessions = mutableListOf<Pair<PeripheralSessionId, Throwable?>>()
    val subscriptionUpdates =
        mutableListOf<Pair<PeripheralSessionId, Set<GattCharacteristicId>>>()
    val maximumLengthUpdates = mutableListOf<Pair<PeripheralSessionId, Int?>>()
    val readinessEvents = mutableListOf<NotificationReadiness>()
    val requests = mutableListOf<BackendGattServerRequest>()
    val platformFailures = mutableListOf<Throwable>()

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
        subscriptionUpdates += sessionId to subscriptions.toSet()
    }

    override fun onMaximumUpdateValueLengthChanged(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) {
        maximumLengthUpdates += sessionId to maximumUpdateValueLength
    }

    override fun onNotificationReady(readiness: NotificationReadiness) {
        readinessEvents += readiness
    }

    override fun onRequest(request: BackendGattServerRequest) {
        requests += request
    }

    override fun onPlatformFailure(cause: Throwable) {
        platformFailures += cause
    }
}
