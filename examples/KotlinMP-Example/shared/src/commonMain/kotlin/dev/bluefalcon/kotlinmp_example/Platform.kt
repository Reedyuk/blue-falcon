package dev.bluefalcon.kotlinmp_example

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform