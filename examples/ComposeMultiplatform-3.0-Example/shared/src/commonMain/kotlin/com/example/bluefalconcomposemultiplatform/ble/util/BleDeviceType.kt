package com.example.bluefalconcomposemultiplatform.ble.util

/**
 * Classifies a BLE peripheral into a known device category using the
 * device name and/or the advertised / discovered service UUIDs.
 */
enum class BleDeviceType {
    WATCH,
    HEADPHONES,
    HEART_RATE_MONITOR,
    SPEAKER,
    THERMOMETER,
    WEIGHT_SCALE,
    CYCLING,
    RUNNING,
    HID,         // keyboard, mouse, game controller
    PHONE,
    UNKNOWN;

    companion object {
        /**
         * Detect device type from name keywords and service UUIDs.
         * Service UUID matching takes priority over name matching.
         */
        fun detect(name: String?, serviceUuids: List<String> = emptyList()): BleDeviceType {
            // Service UUID-based detection (most reliable)
            for (uuid in serviceUuids.map { it.lowercase() }) {
                when {
                    uuid.contains("180d") -> return HEART_RATE_MONITOR   // Heart Rate
                    uuid.contains("1816") -> return CYCLING               // Cycling Speed & Cadence
                    uuid.contains("1818") -> return CYCLING               // Cycling Power
                    uuid.contains("1814") -> return RUNNING               // Running Speed & Cadence
                    uuid.contains("1809") -> return THERMOMETER           // Health Thermometer
                    uuid.contains("181d") -> return WEIGHT_SCALE          // Weight Scale
                    uuid.contains("1812") -> return HID                   // Human Interface Device
                }
            }

            // Name-based detection (fallback)
            val lower = name?.lowercase() ?: return UNKNOWN
            return when {
                lower.containsAny("watch", "band", "fenix", "forerunner", "vivofit",
                    "instinct", "venu", "galaxy watch", "apple watch", "mi band",
                    "amazfit", "fitbit", "versa", "sense") -> WATCH

                lower.containsAny("headphone", "headset", "earphone", "earbud",
                    "airpod", "buds", "jabra", "bose", "sony wh", "wh-", "wf-",
                    "anker", "sennheiser", "beats") -> HEADPHONES

                lower.containsAny("hrm", "heart rate", "hr monitor", "polar h",
                    "tickr", "wahoo") -> HEART_RATE_MONITOR

                lower.containsAny("speaker", "soundlink", "charge ", "flip ",
                    "pulse ", "jbl", "ue boom", "wonderboom") -> SPEAKER

                lower.containsAny("thermometer", "temp sensor") -> THERMOMETER

                lower.containsAny("scale", "weight") -> WEIGHT_SCALE

                lower.containsAny("cycling", "speed sensor", "cadence", "powermeter",
                    "stages", "quarq", "garmin speed") -> CYCLING

                lower.containsAny("run sensor", "footpod", "stryd") -> RUNNING

                lower.containsAny("keyboard", "mouse", "trackpad", "controller",
                    "remote", "hid") -> HID

                lower.containsAny("iphone", "pixel", "samsung galaxy s",
                    "android phone") -> PHONE

                else -> UNKNOWN
            }
        }

        private fun String.containsAny(vararg keywords: String) =
            keywords.any { this.contains(it) }
    }
}

/**
 * Converts RSSI to a human-readable proximity label.
 * These are approximations — real-world distance depends on environment.
 */
fun rssiToProximityLabel(rssi: Float): String = when {
    rssi > -50  -> "Very close"
    rssi > -65  -> "Near"
    rssi > -75  -> "Moderate"
    rssi > -85  -> "Far"
    else        -> "Very far"
}
