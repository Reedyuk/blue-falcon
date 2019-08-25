package dev.bluefalcon.views

import android.os.Build
import androidx.annotation.RequiresApi
import dev.bluefalcon.activities.DeviceActivity
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
            textView(if (viewModel.isConnected) "Connected" else "Connecting...")
            listView {
                adapter = viewModel.deviceAdapter
            }
        }
    }
}