package com.example.bluetoothtest

/**
 * Reads the phone's Wi-Fi Direct device name for the Settings info sheet only. Every part runs on the radio looper
 * (the whole body is posted), and the name is memory-only: never logged, persisted or sent. [AM 17]
 */
internal class DirectDeviceName(
    private val canRead: () -> Boolean,
    private val openPort: () -> P2pPort,
    private val scheduler: DirectGroup.Scheduler,
    private val clock: () -> Long,
) {
    @Volatile
    var cached: String? = null
        private set

    private var cachedAt = 0L

    /** [then] runs exactly once, on the radio looper. */
    fun refresh(then: (String?) -> Unit) {
        scheduler.post(0) {
            val current = cached
            if (current != null && clock() - cachedAt < DirectLimits.DEVICE_NAME_CACHE_MS) {
                then(current)
                return@post
            }
            if (!canRead()) {
                then(null)
                return@post
            }
            val port = openPort()
            if (!port.open {}) {
                then(null)
                return@post
            }
            var answered = false
            var timeout: DirectGroup.Cancelable? = null
            fun answer(name: String?) {
                if (answered) return
                answered = true
                timeout?.cancel()
                port.closeChannel()
                val value = name?.takeIf { it.isNotBlank() }
                if (value != null) {
                    cached = value
                    cachedAt = clock()
                }
                then(value)
            }
            timeout = scheduler.post(DirectLimits.DEVICE_NAME_TIMEOUT_MS) { answer(cached) }
            port.requestDeviceName { name -> answer(name) }
        }
    }
}
