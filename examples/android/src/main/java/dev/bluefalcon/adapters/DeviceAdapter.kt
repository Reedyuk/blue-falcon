package dev.bluefalcon.adapters

import android.bluetooth.BluetoothGattService
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import dev.bluefalcon.viewModels.DeviceViewModel
import org.jetbrains.anko.relativeLayout
import org.jetbrains.anko.textView

class DeviceAdapter(viewModel : DeviceViewModel) : BaseAdapter() {
    private var list : List<BluetoothGattService> = viewModel.services

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            relativeLayout {
                textView(item.instanceId) {
                    textSize = 32f
                }
            }
        }
    }

    override fun getItem(position : Int) : BluetoothGattService = list[position]

    override fun getCount() : Int = list.size

    override fun getItemId(position : Int) : Long = position.toLong()

}