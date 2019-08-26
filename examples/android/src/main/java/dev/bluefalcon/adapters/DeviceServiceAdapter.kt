package dev.bluefalcon.adapters

import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import dev.bluefalcon.viewModels.DeviceServiceViewModel
import org.jetbrains.anko.dip
import org.jetbrains.anko.padding
import org.jetbrains.anko.relativeLayout
import org.jetbrains.anko.textView

class DeviceServiceAdapter(private val viewModel : DeviceServiceViewModel) : BaseAdapter() {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            relativeLayout {
                textView(item.uuid.toString()) {
                    padding = dip(10)
                }
            }
        }
    }

    override fun getItem(position : Int) : BluetoothGattCharacteristic = viewModel.characteristics[position]

    override fun getCount() : Int = viewModel.characteristics.size

    override fun getItemId(position : Int) : Long = position.toLong()

}