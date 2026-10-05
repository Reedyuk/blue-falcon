package dev.bluefalcon.peripheral.internal

import kotlinx.coroutines.flow.MutableStateFlow

/** Covers ingress, active processing, public queue and pending native response ownership. */
internal class PeripheralRequestAdmission(
    private val maximumItems: Int = 256,
    private val maximumBytes: Long = 1_048_576,
) {
    data class Usage(val items: Int = 0, val bytes: Long = 0)
    private val usage = MutableStateFlow(Usage())
    fun acquire(bytes: Long, responseRequired: Boolean): Permit? {
        require(bytes >= 0)
        while (true) {
            val current = usage.value
            if (current.items >= maximumItems || bytes > maximumBytes - current.bytes) return null
            if (usage.compareAndSet(current, Usage(current.items + 1, current.bytes + bytes))) {
                return Permit(responseRequired) {
                    while (true) {
                        val retained = usage.value
                        if (usage.compareAndSet(retained, Usage(retained.items - 1, retained.bytes - bytes))) break
                    }
                }
            }
        }
    }
    class Permit(responseRequired: Boolean, private val release: () -> Unit) {
        private val done = MutableStateFlow(if (responseRequired) 0 else RESPONSE_DONE)
        fun delivered() = finish(DELIVERY_DONE)
        fun responseCompleted() = finish(RESPONSE_DONE)
        private fun finish(flag: Int) {
            while (true) {
                val current = done.value
                if (current and flag != 0) return
                val next = current or flag
                if (done.compareAndSet(current, next)) {
                    if (next == (DELIVERY_DONE or RESPONSE_DONE)) release()
                    return
                }
            }
        }
        private companion object {
            const val DELIVERY_DONE = 1
            const val RESPONSE_DONE = 2
        }
    }
}

internal class PeripheralResourceOverflowException : IllegalStateException("Peripheral callback resource capacity exceeded")
