package dev.bluefalcon

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.lang.ref.WeakReference

internal object BluetoothStateMonitor {

    private val instances = mutableListOf<WeakReference<BlueFalcon>>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val adapterOn = state == BluetoothAdapter.STATE_ON
            val adapterOff = state == BluetoothAdapter.STATE_OFF

            if (!adapterOn && !adapterOff) return

            synchronized(instances) {
                instances.removeAll { it.get() == null }
                instances.mapNotNull { it.get() }.forEach { instance ->
                    instance.onAdapterStateChanged(adapterOn)
                }
            }
        }
    }

    fun register(context: Context, instance: BlueFalcon) {
        synchronized(instances) {
            instances.removeAll { it.get() == null }
            if (instances.none { it.get() === instance }) {
                instances.add(WeakReference(instance))
            }
            if (!registered) {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.applicationContext.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    context.applicationContext.registerReceiver(receiver, filter)
                }
                registered = true
            }
        }
    }

    fun unregister(context: Context, instance: BlueFalcon) {
        synchronized(instances) {
            instances.removeAll { it.get() == null || it.get() === instance }
            if (instances.isEmpty() && registered) {
                try {
                    context.applicationContext.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {}
                registered = false
            }
        }
    }
}
