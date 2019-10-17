package presentation.adapters

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import org.jetbrains.anko.linearLayout
import org.jetbrains.anko.padding
import org.jetbrains.anko.textView
import presentation.viewmodels.DevicesItemViewModel

class DevicesAdapter(var deviceViewModels : List<DevicesItemViewModel>) : BaseAdapter() {

    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            linearLayout {
                textView(item.displayName) {
                    textSize = 20f
                    padding = 10
                }
            }
        }
    }

    override fun getItem(position : Int) : DevicesItemViewModel = deviceViewModels[position]

    override fun getCount() : Int = deviceViewModels.size

    override fun getItemId(position : Int) : Long = position.toLong()

}