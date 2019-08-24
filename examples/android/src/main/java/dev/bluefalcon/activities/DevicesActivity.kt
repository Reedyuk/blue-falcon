package dev.bluefalcon.activities

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.annotation.RequiresApi
import dev.bluefalcon.viewModels.DevicesViewModel
import org.jetbrains.anko.*

class DevicesActivity : AppCompatActivity() {

    private val devicesViewModel = DevicesViewModel(this)

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicesViewModel.setupBluetooth()
        devicesViewModel.devicesActivityUI.setContentView(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        devicesViewModel.setupBluetooth()
    }
}

