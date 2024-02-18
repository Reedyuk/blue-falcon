package com.example.bluefalconcomposemultiplatform.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.example.bluefalconcomposemultiplatform.App
import com.example.bluefalconcomposemultiplatform.di.AppModule

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (needToAskForRuntimePermissions()) {
            askForRuntimePermissions {
                // Do something when permission granted
            }
        }

        setContent {
            App(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = false,
                appModule = AppModule(LocalContext.current.applicationContext)
            )
        }
    }

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