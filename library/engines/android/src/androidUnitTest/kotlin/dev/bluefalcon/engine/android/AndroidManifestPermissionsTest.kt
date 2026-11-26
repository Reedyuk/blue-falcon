package dev.bluefalcon.engine.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the permissions this library merges into every app. Unit tests run with the module
 * directory as working directory, so the source manifest is read directly.
 */
class AndroidManifestPermissionsTest {
    private val permissions: Map<String, Element> by lazy {
        val manifest = File("src/androidMain/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val nodes = document.getElementsByTagName("uses-permission")
        (0 until nodes.length).map { nodes.item(it) as Element }
            .associateBy { it.getAttributeNS(AndroidNamespace, "name") }
    }

    @Test
    fun legacyBluetoothAndLocationPermissionsStopAtAndroid11() {
        listOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        ).forEach { name ->
            assertEquals("30", permissions.getValue(name).androidAttribute("maxSdkVersion"), name)
        }
    }

    @Test
    fun scanPermissionAssertsNeverForLocation() {
        val scan = permissions.getValue("android.permission.BLUETOOTH_SCAN")

        assertEquals("neverForLocation", scan.androidAttribute("usesPermissionFlags"))
        assertNull(scan.androidAttribute("maxSdkVersion"))
    }

    @Test
    fun android12PermissionsHaveNoSdkLimit() {
        listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
        ).forEach { name ->
            assertNull(permissions.getValue(name).androidAttribute("maxSdkVersion"), name)
        }
        assertEquals(7, permissions.size)
    }

    private fun Element.androidAttribute(name: String): String? =
        getAttributeNS(AndroidNamespace, name).ifEmpty { null }

    private companion object {
        const val AndroidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
