package dev.bluefalcon.adapters

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import dev.bluefalcon.BluetoothService
import dev.bluefalcon.viewModels.DeviceViewModel
import org.jetbrains.anko.dip
import org.jetbrains.anko.padding
import org.jetbrains.anko.relativeLayout
import org.jetbrains.anko.textView

class DeviceAdapter(private val viewModel : DeviceViewModel) : BaseAdapter() {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            relativeLayout {
                textView(item.name) {
                    padding = dip(10)
                }
            }
        }
    }

    override fun getItem(position : Int) : BluetoothService = viewModel.services[position]

    override fun getCount() : Int = viewModel.services.size

    override fun getItemId(position : Int) : Long = position.toLong()

}