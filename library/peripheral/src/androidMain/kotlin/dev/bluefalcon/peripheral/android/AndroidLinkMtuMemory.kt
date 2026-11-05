package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.bluefalcon.core.Logger

/**
 * Remembers the ATT MTU last reported for a remote device while its LE link is up.
 *
 * A central negotiates the ATT MTU once per LE link. When a new GATT server session (or a new
 * [android.bluetooth.BluetoothGattServer]) starts on a link that is still up, Android reports
 * no new `onMtuChanged`, and has no public API to read a server connection's MTU.
 *
 * Entries are only accepted after link-drop observation has started, so every remembered value
 * can be forgotten on `ACTION_ACL_DISCONNECTED` or when the adapter leaves `STATE_ON`. Forgetting
 * is always safe: a session without a remembered MTU keeps the ATT default.
 */
internal class AndroidLinkMtuMemory(private val maximumEntries: Int = 256) {
    private val lock = Any()
    private var observing = false
    private val mtus = LinkedHashMap<String, Int>()

    fun startObserving() {
        synchronized(lock) { observing = true }
    }

    fun remember(address: String, mtu: Int) {
        synchronized(lock) {
            if (!observing) return
            mtus.remove(address)
            if (mtus.size >= maximumEntries) mtus.remove(mtus.keys.first())
            mtus[address] = mtu
        }
    }

    fun recall(address: String): Int? = synchronized(lock) { mtus[address] }

    fun forget(address: String) {
        synchronized(lock) { mtus.remove(address) }
    }

    fun clear() {
        synchronized(lock) { mtus.clear() }
    }

    fun onBroadcast(action: String?, address: String?, adapterState: Int) {
        when (action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (address != null) forget(address) else clear()
            BluetoothAdapter.ACTION_STATE_CHANGED -> if (adapterState != BluetoothAdapter.STATE_ON) clear()
        }
    }
}

/** Process-wide link MTU memory shared by every framework GATT server instance. */
internal object AndroidLinkMtus {
    val memory = AndroidLinkMtuMemory()
    private val registration = Any()
    private var receiver: BroadcastReceiver? = null

    /** Registers link-drop observation once per process; without it nothing is remembered. */
    fun observe(context: Context, logger: Logger?): Unit = synchronized(registration) {
        if (receiver != null) return
        val linkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                @Suppress("DEPRECATION")
                val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                memory.onBroadcast(
                    action = intent?.action,
                    address = device?.address,
                    adapterState = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        ?: BluetoothAdapter.ERROR,
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            // Both actions are protected system broadcasts.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(linkReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(linkReceiver, filter)
            }
            receiver = linkReceiver
            memory.startObserving()
        } catch (cause: Throwable) {
            logger?.warn("Failed to observe Android LE link drops; link MTU will not be remembered", cause)
        }
    }
}
