package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.internal.PeripheralRequestAdmission

/** Admission precedes the platform callback's first owned payload copy. */
internal class AndroidPayloadAdmission(
    private val admission: PeripheralRequestAdmission = PeripheralRequestAdmission(),
) {
    fun dispatch(bytes: Long, onRejected: () -> Unit, callback: () -> Unit) {
        val permit = admission.acquire(bytes, false)
        if (permit == null) { onRejected(); return }
        try { callback() } finally { permit.delivered() }
    }
}
