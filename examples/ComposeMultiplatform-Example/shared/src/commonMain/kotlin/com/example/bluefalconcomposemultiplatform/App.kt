package com.example.bluefalconcomposemultiplatform

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bluefalconcomposemultiplatform.ble.presentation.BluetoothDeviceViewModel
import com.example.bluefalconcomposemultiplatform.ble.presentation.component.DeviceScanView
import com.example.bluefalconcomposemultiplatform.core.presentation.BlueFalconTheme
import com.example.bluefalconcomposemultiplatform.di.AppModule
import com.example.bluefalconcomposemultiplatform.ui.theme.Typography
import dev.icerock.moko.mvvm.compose.getViewModel
import dev.icerock.moko.mvvm.compose.viewModelFactory

@Composable
fun App(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    appModule: AppModule
) {
    BlueFalconTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor
    ) {
        val viewModel = getViewModel(
            key = "bluetooth-device-screen",
            factory = viewModelFactory {
                BluetoothDeviceViewModel(appModule.blueFalcon)
            }
        )

        val state by viewModel.deviceState.collectAsState()

        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                Text(
                    "BlueFalcon Compose App",
                    fontStyle = Typography.titleLarge.fontStyle,
                    fontSize = Typography.titleLarge.fontSize,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp).fillMaxWidth()
                )

                DeviceScanView(
                    state = state,
                    onEvent = viewModel::onEvent
                )
            }


        }
    }
}