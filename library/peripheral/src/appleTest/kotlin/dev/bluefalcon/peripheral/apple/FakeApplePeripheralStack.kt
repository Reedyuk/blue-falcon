package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.peripheral.PeripheralConfig

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
