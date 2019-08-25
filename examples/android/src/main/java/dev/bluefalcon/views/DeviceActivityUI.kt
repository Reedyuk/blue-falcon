package dev.bluefalcon.views

import android.graphics.Color
import android.graphics.Typeface.DEFAULT_BOLD
import android.os.Build
import androidx.annotation.RequiresApi
import dev.bluefalcon.activities.DeviceActivity
import dev.bluefalcon.extensions.bindString
import dev.bluefalcon.viewModels.DeviceViewModel
import org.jetbrains.anko.*

class DeviceActivityUI(private val viewModel: DeviceViewModel) : AnkoComponent<DeviceActivity> {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun createView(ui : AnkoContext<DeviceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView(viewModel.bluetoothPeripheral.bluetoothDevice.address).lparams {
                    centerHorizontally()
                }
            }
            textView {
                bindString(viewModel.connectionStatus)
                padding = 10
            }
            textView("Services") {
                typeface = DEFAULT_BOLD
                padding = 10
            }
            listView {
                adapter = viewModel.deviceAdapter
            }
        }
    }
}