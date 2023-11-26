package dev.bluefalcon.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import dev.bluefalcon.viewModels.DevicesViewModel
import org.jetbrains.anko.setContentView

class DevicesActivity : AppCompatActivity() {

    private val devicesViewModel = DevicesViewModel(this)

    private val doWhenPermissionAcquired = { devicesViewModel.setupBluetooth() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (needToAskForRuntimePermissions()) {
            askForRuntimePermissions(doWhenPermissionAcquired)
        } else {
            doWhenPermissionAcquired()
        }

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

    /**
     * NOTE: This is only an example. As such, all required runtime permission steps are not being
     * followed: https://developer.android.com/training/permissions/requesting#already-granted
     *
     * @return true if it's necessary to ask for runtime permission.
     */
    private fun askForRuntimePermissions(doWhenPermissionAcquired: ()-> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->

                if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                    permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                    permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                ) {
                    doWhenPermissionAcquired()
                } else {
                    Toast.makeText(this, "Cannot scan without permission.", Toast.LENGTH_LONG)
                        .show()
                }
            }.apply {
                launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
        }
    }

    private fun needToAskForRuntimePermissions() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&

            ((ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||

              ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||

              ActivityCompat.checkSelfPermission(
              this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED))
}

