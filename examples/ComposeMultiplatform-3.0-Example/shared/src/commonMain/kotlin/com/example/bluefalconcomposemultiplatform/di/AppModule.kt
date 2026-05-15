package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothAdvertiser
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin

expect class AppModule {
    val blueFalcon: BlueFalcon
    val fotaPlugin: NordicFotaPlugin
    val advertiser: BluetoothAdvertiser
}