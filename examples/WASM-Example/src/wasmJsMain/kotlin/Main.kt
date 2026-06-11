import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.engine.js.JsEngine
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

private val scope = CoroutineScope(Dispatchers.Default)
private val blueFalcon = BlueFalcon(JsEngine())

/** Flat registry of discovered characteristics, referenced by index from the DOM. */
private val characteristics = mutableListOf<BluetoothCharacteristic>()
private var connectedPeripheral: BluetoothPeripheral? = null

fun main() {
    log("Blue Falcon Kotlin/Wasm example ready.")
    log("Open over https:// or http://localhost in a Web Bluetooth capable browser (Chrome/Edge).")

    byId("scanBtn").addEventListener("click", { _ ->
        // Run synchronously up to the first suspension so the click's user-gesture
        // activation — which Web Bluetooth's requestDevice() requires — is preserved.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scanAndConnect() }
    })

    // One delegated listener handles every dynamically-rendered characteristic button.
    byId("results").addEventListener("click", { event -> onResultsClick(event) })

    // Surface every notification the engine emits. This is what exercises the
    // `characteristicvaluechanged` bridge in the wasmJs engine.
    scope.launch {
        blueFalcon.engine.characteristicNotifications.collect { notification ->
            log("🔔 ${shortUuid(notification.characteristic.uuid.toString())}: ${render(notification.value)}")
        }
    }
}

private suspend fun scanAndConnect() {
    // Wrap the whole flow: UUID parsing, the chooser, and GATT calls can all throw,
    // and an unguarded throw here would crash the coroutine instead of being shown.
    try {
        val filters = parseFilters(inputValue("uuid"))
        log(if (filters.isEmpty()) "Requesting any device (no service access declared — discovery will be blocked)…"
            else "Requesting any device; declaring access to service ${filters.first().uuid}…")

        val before = blueFalcon.peripherals.value
        blueFalcon.scan(filters)

        val after = blueFalcon.peripherals.value
        val peripheral = (after - before).firstOrNull() ?: after.lastOrNull()
        if (peripheral == null) {
            log("No device selected.")
            return
        }
        connectedPeripheral = peripheral

        log("Selected '${peripheral.name ?: "(unnamed)"}' [${peripheral.uuid}] — connecting…")
        blueFalcon.connect(peripheral)
        log("Connected. Discovering services…")

        // Discover the named service specifically (some peripherals don't return
        // anything from a bulk getPrimaryServices() but do for a specific UUID).
        // Falls back to discover-all when no UUID was entered.
        blueFalcon.discoverServices(peripheral, filters.map { it.uuid })
        log("Found ${peripheral.services.size} service(s).")
        peripheral.services.forEach { service ->
            blueFalcon.discoverCharacteristics(peripheral, service)
        }
        renderCharacteristics(peripheral)
    } catch (t: Throwable) {
        log("Scan/connect failed: ${t.message}")
    }
}

/**
 * Turns the raw input into a (possibly empty) filter list. Accepts a blank value
 * (any device), a 16-/32-bit short form, or a full UUID, with an optional `0x`
 * prefix — e.g. `0x1825`, `1825`, or `00001825-0000-1000-8000-00805f9b34fb`.
 */
private fun parseFilters(raw: String): List<ServiceFilter> {
    val cleaned = raw.trim().removePrefix("0x").removePrefix("0X").trim()
    return if (cleaned.isEmpty()) emptyList() else listOf(ServiceFilter(cleaned.toUuid()))
}

private fun renderCharacteristics(peripheral: BluetoothPeripheral) {
    characteristics.clear()
    val html = StringBuilder()
    html.append("<h3>").append(peripheral.name ?: "(unnamed device)").append("</h3>")
    peripheral.services.forEach { service ->
        html.append("<div class='service'>Service <code>")
            .append(shortUuid(service.uuid.toString())).append("</code></div>")
        service.characteristics.forEach { characteristic ->
            val index = characteristics.size
            characteristics.add(characteristic)
            html.append("<div class='char'><code>")
                .append(shortUuid(characteristic.uuid.toString())).append("</code> ")
            html.append(button("read", index, "Read"))
            html.append(button("subscribe", index, "Subscribe"))
            html.append(button("unsubscribe", index, "Unsubscribe"))
            html.append(button("write", index, "Write"))
            html.append("</div>")
        }
    }
    byId("results").innerHTML = html.toString()
    log("Discovered ${characteristics.size} characteristic(s).")
}

private fun onResultsClick(event: Event) {
    val target = event.target ?: return
    val element = target as Element
    val action = element.getAttribute("data-action") ?: return
    val index = element.getAttribute("data-idx")?.toIntOrNull() ?: return
    val characteristic = characteristics.getOrNull(index) ?: return
    val peripheral = connectedPeripheral ?: return

    scope.launch {
        try {
            when (action) {
                "read" -> {
                    blueFalcon.readCharacteristic(peripheral, characteristic)
                    log("read ${shortUuid(characteristic.uuid.toString())}: " +
                        (characteristic.value?.let(::render) ?: "(null)"))
                }
                "subscribe" -> {
                    blueFalcon.notifyCharacteristic(peripheral, characteristic, true)
                    log("subscribed to ${shortUuid(characteristic.uuid.toString())}")
                }
                "unsubscribe" -> {
                    blueFalcon.notifyCharacteristic(peripheral, characteristic, false)
                    log("unsubscribed from ${shortUuid(characteristic.uuid.toString())}")
                }
                "write" -> {
                    val text = inputValue("writeValue")
                    blueFalcon.writeCharacteristic(peripheral, characteristic, text)
                    log("wrote \"$text\" to ${shortUuid(characteristic.uuid.toString())}")
                }
            }
        } catch (t: Throwable) {
            log("Error on '$action': ${t.message}")
        }
    }
}

// ---- small DOM/format helpers ----

private fun button(action: String, index: Int, label: String): String =
    "<button data-action='$action' data-idx='$index'>$label</button> "

private fun byId(id: String): Element =
    document.getElementById(id) ?: error("Missing #$id element")

private fun inputValue(id: String): String =
    (document.getElementById(id) as HTMLInputElement).value

private fun log(message: String) {
    val element = document.getElementById("log") ?: return
    element.textContent = (element.textContent ?: "") + message + "\n"
    element.scrollTop = element.scrollHeight.toDouble()
}

private fun render(value: ByteArray): String {
    val hex = value.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "[$hex] \"${value.decodeToString()}\""
}

/** Collapses the Bluetooth base UUID to its 16-bit short form when applicable. */
private fun shortUuid(uuid: String): String =
    if (uuid.startsWith("0000") && uuid.endsWith("-0000-1000-8000-00805f9b34fb")) {
        uuid.substring(4, 8)
    } else {
        uuid
    }
