package dev.bluefalcon.adapters

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
                textView(item.toString()) {
                    padding = dip(10)
                }
            }
        }
    }

    override fun getItem(position : Int) : Int = 1

    override fun getCount() : Int = 1

    override fun getItemId(position : Int) : Long = position.toLong()

}