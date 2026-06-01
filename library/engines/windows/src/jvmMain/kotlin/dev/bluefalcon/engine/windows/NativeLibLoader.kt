package dev.bluefalcon.engine.windows

import java.io.File

internal object NativeLibLoader {
    fun load(resourcePath: String) {
        val stream = NativeLibLoader::class.java.classLoader?.getResourceAsStream(resourcePath)
            ?: throw UnsatisfiedLinkError("Native library not found in resources: $resourcePath")
        val ext = resourcePath.substringAfterLast('.')
        val tmp = File.createTempFile("bluefalcon-native", ".$ext")
        tmp.deleteOnExit()
        stream.use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        System.load(tmp.absolutePath)
    }
}
