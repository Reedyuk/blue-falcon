package dev.bluefalcon.engine.macos.jvm

import java.io.File

internal object NativeLibLoader {
    fun load(resourcePath: String) {
        val stream = NativeLibLoader::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: throw UnsatisfiedLinkError("Native library not found in resources: $resourcePath")
        val ext = resourcePath.substringAfterLast('.')
        val tmp = File.createTempFile("bluefalcon-native", ".$ext")
        tmp.deleteOnExit()
        stream.use { it.copyTo(tmp.outputStream()) }
        System.load(tmp.absolutePath)
    }
}