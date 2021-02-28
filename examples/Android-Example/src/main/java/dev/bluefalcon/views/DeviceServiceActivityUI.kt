package dev.bluefalcon.views

import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.viewModels.DeviceServiceViewModel
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk27.coroutines.onItemClick

class DeviceServiceActivityUI(private val viewModel: DeviceServiceViewModel) : AnkoComponent<DeviceServiceActivity> {

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun createView(ui : AnkoContext<DeviceServiceActivity>) = with(ui) {
        verticalLayout {
            relativeLayout {
                textView(viewModel.service.uuid.toString()).lparams {
                    centerHorizontally()
                }
            }
            textView("Characteristics") {
                typeface = Typeface.DEFAULT_BOLD
            }
            listView {
                adapter = viewModel.deviceServiceAdapter
            }
            padding = dip(10)
        }
    }

}