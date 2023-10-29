package dev.bluefalcon.kotlinmp_example.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import dev.bluefalcon.BluetoothPermissionException
import dev.bluefalcon.kotlinmp_example.viewmodels.DevicesViewModel

@Composable
fun DevicesView(activity: Activity, viewModel: DevicesViewModel) {

    val devices = viewModel.devices.collectAsState(emptyList())

    Column(modifier = Modifier.padding(10.dp)) {
        Row {
            Text(text = "Devices")
        }
        Row {
            Button(
                onClick = {
                    try {
                        viewModel.scan()
                    } catch (exception: BluetoothPermissionException) {
                        if (ActivityCompat.checkSelfPermission(
                                activity,
                                Manifest.permission.BLUETOOTH_SCAN
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            val permission = arrayOf(Manifest.permission.BLUETOOTH_SCAN)
                            ActivityCompat.requestPermissions(activity, permission, 0)
                        }
                    }
                },
                modifier = Modifier
                    .width(100.dp)
                    .height(50.dp)
            ) {
                Text(text = "Scan")
            }
        }
        Row {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                devices.value.forEach { device ->
                    item {
                        Row {
                            Text(text = device.name ?: device.uuid)
                        }
                    }
                }
            }
        }
    }
}
