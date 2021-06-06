package dev.bluefalcon.activities

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import dev.bluefalcon.viewModels.DevicesViewModel
import org.jetbrains.anko.*

class DevicesActivity : AppCompatActivity() {

    private val devicesViewModel = DevicesViewModel(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicesViewModel.setupBluetooth()
        devicesViewModel.devicesActivityUI.setContentView(this)
    }

    override fun onResume() {
        super.onResume()
        devicesViewModel.addDelegate()
    }

    override fun onPause() {
        super.onPause()
        devicesViewModel.removeDelegate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        devicesViewModel.setupBluetooth()
    }
}

