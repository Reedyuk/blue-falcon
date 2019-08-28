package dev.bluefalcon.adapters

import android.bluetooth.BluetoothGattCharacteristic
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.annotation.RequiresApi
import dev.bluefalcon.log
import dev.bluefalcon.viewModels.DeviceServiceViewModel
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onClick

class DeviceServiceAdapter(private val viewModel : DeviceServiceViewModel) : BaseAdapter() {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun getView(i : Int, v : View?, parent : ViewGroup?) : View {
        val item = getItem(i)
        return with(parent!!.context) {
            verticalLayout {
                verticalLayout {
                    linearLayout {
                        textView("ID: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                        textView(item.uuid.toString())
                    }
                    linearLayout {
                        textView("Value: ") {
                            typeface = Typeface.DEFAULT_BOLD
                        }
                    }
                }
                linearLayout {
                    button("Read") {
                        onClick {
                            viewModel.readCharacteristicTapped(item)
                        }
                    }
                    button("Notify") {
                        onClick {
                            viewModel.notifyCharacteristicTapped(item)
                        }
                    }
                    button("Write") {
                        onClick {
                            viewModel.writeCharactersticTapped(item)
                        }
                    }
                }
            }
        }
    }

    override fun getItem(position : Int) : BluetoothGattCharacteristic = viewModel.characteristics[position]

    override fun getCount() : Int = viewModel.characteristics.size

    override fun getItemId(position : Int) : Long = position.toLong()

}