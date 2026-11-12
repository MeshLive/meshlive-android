package com.example.bluetoothtest

/** The random responder port of the host side: reserved first, published in the specifier, bound after the data path is up. [R A4-1] */
internal object AwarePorts {
    const val MIN = 49_152
    const val MAX = 65_535

    fun randomPort(random: java.util.Random = java.security.SecureRandom()): Int = MIN + random.nextInt(MAX - MIN + 1)
}
