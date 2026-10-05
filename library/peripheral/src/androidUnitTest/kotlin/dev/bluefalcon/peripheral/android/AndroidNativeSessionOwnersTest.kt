package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlin.test.*

class AndroidNativeSessionOwnersTest {
    @Test fun asyncRetirementCannotLetLateAddressOnlyDisconnectNameReplacement() {
        val owners = AndroidNativeSessionOwners<Any>()
        val id = PeripheralSessionId("same-address")
        val a = requireNotNull(owners.admit(id) { Any() })
        assertTrue(owners.retire(id, a))
        var replacementCreated = false
        assertNull(owners.admit(id) { replacementCreated = true; Any() })
        assertFalse(replacementCreated)
        assertNull(owners.remove(id), "Late A disconnect looked up a replacement")
        assertNull(owners.admit(id) { Any() }, "An address-only terminal cannot prove all retired callbacks drained")
        owners.clear()
        val b = requireNotNull(owners.admit(id) { Any() })
        assertFalse(owners.retire(id, a))
        assertSame(b, owners[id])
    }
    @Test fun retiredAndLiveOwnersShareOneStrictSlotBound() {
        val owners = AndroidNativeSessionOwners<Any>(maximumSlots = 2)
        val first = PeripheralSessionId("a"); val second = PeripheralSessionId("b")
        val a = requireNotNull(owners.admit(first) { Any() })
        assertTrue(owners.retire(first, a))
        val b = requireNotNull(owners.admit(second) { Any() })
        assertNull(owners.admit(PeripheralSessionId("c")) { Any() })
        assertSame(b, owners.remove(second))
        assertNotNull(owners.admit(PeripheralSessionId("c")) { Any() })
    }
}
