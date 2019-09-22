package dev.bluefalcon.adapters

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import dev.bluefalcon.BluetoothPeripheral
import org.jetbrains.anko.linearLayout
import org.jetbrains.anko.padding
import org.jetbrains.anko.textView

class DevicesAdapter(var devices : List<BluetoothPeripheral>) : BaseAdapter() {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            linearLayout {
                textView(item.bluetoothDevice.address) {
                    textSize = 20f
                    padding = 10
                }
                textView(item.bluetoothDevice.name) {
                    textSize = 12f
                    padding = 10
                }
            }
        }
    }

    override fun getItem(position : Int) : BluetoothPeripheral = devices[position]

    override fun getCount() : Int = devices.size

    override fun getItemId(position : Int) : Long = position.toLong()

}