package dev.bluefalcon.kotlinmp_example.android

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dawidraszka.composepermissionhandler.core.ExperimentalPermissionHandlerApi
import com.dawidraszka.composepermissionhandler.core.PermissionHandlerHost
import com.dawidraszka.composepermissionhandler.core.PermissionHandlerHostState
import com.dawidraszka.composepermissionhandler.core.PermissionHandlerResult
import dev.bluefalcon.kotlinmp_example.viewmodels.DevicesViewModel
import kotlinx.coroutines.launch
import java.lang.Exception

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalPermissionHandlerApi::class)
@Composable
fun DevicesView(viewModel: DevicesViewModel) {

    val permissionHandlerHostState = PermissionHandlerHostState(permissionList = listOf(
        android.Manifest.permission.BLUETOOTH,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    ))
    PermissionHandlerHost(hostState = permissionHandlerHostState)
    val coroutineScope = rememberCoroutineScope()

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
                    } catch (exception: SecurityException) {
                        coroutineScope.launch {
                            when (permissionHandlerHostState.handlePermissions()) {
                                PermissionHandlerResult.GRANTED -> {
                                    println("Granted")
                                }
                                PermissionHandlerResult.DENIED -> {
                                    println("Denied")
                                }
                                PermissionHandlerResult.DENIED_NEXT_RATIONALE -> {
                                    println("Denied next rational")
                                }
                            }
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
