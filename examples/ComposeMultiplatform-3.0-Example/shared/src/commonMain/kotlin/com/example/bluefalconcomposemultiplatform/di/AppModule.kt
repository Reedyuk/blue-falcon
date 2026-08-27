package com.example.bluefalconcomposemultiplatform.di

import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin
import dev.bluefalcon.plugins.proximity.ProximityPlugin

expect class AppModule {
    val blueFalcon: BlueFalcon
    val fotaPlugin: NordicFotaPlugin
    val proximityPlugin: ProximityPlugin
    val advertiser: BluetoothAdvertiser
    val peripheralRuntime: PeripheralExampleRuntime?
}