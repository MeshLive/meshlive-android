package com.example.bluetoothtest

/**
 * The build tag of a BMH2 *link* hello (PROTOCOL "Build tag in the link hello and update
 * verification"): transport-mask bit 7 ([LINK_HELLO_BIT]) says the first two bytes of the hello's
 * 32-byte nonce are `bit 15 = project release key, bits 14..0 = release number`, and the other 30
 * are random. Older builds read the nonce as 32 opaque bytes and the mask only with `and` tests, so
 * they interoperate unchanged. The tag is inside the signed, transcript-hashed hello, so only the
 * holder of the identity key can set it. It is a claim about the peer's own build, never proof about
 * its APK, and it only ever decides whether a quiet notice is shown.
 */
data class BuildTag(val releaseNumber: Int, val releaseKey: Boolean) {
    init {
        require(releaseNumber in 1..ReleaseNumber.MAX) { "Invalid release number $releaseNumber" }
    }

    /** The tag as the big-endian u16 written to nonce bytes 0 and 1. */
    fun encode(): Int = (if (releaseKey) RELEASE_KEY_FLAG else 0) or releaseNumber

    companion object {
        /** Link-hello transport-mask bit 7. End-to-end hellos use bit 7 for call video fragments. */
        const val LINK_HELLO_BIT = 1 shl 7
        const val TAG_BYTES = 2
        private const val RELEASE_KEY_FLAG = 0x8000

        /** Null when the release number is 0 (not a valid tag). */
        fun decode(value: Int): BuildTag? {
            val release = value and ReleaseNumber.MAX
            if (release == 0 || value !in 0..0xFFFF) return null
            return BuildTag(release, value and RELEASE_KEY_FLAG != 0)
        }

        /** A copy of [nonce] (32 random bytes) with the tag in bytes 0 and 1. */
        fun applyTo(nonce: ByteArray, tag: BuildTag): ByteArray {
            require(nonce.size == MeshHandshake.NONCE_BYTES) { "A hello nonce is ${MeshHandshake.NONCE_BYTES} bytes" }
            return nonce.copyOf().also {
                val value = tag.encode()
                it[0] = (value ushr 8).toByte()
                it[1] = value.toByte()
            }
        }

        /** The tag of a hello whose signature was already verified; null when it carries none. */
        fun readFrom(hello: SignedHandshakeHello): BuildTag? {
            if (hello.capabilities.transportMask and LINK_HELLO_BIT == 0) return null
            if (hello.nonce.size < TAG_BYTES) return null
            val value = ((hello.nonce[0].toInt() and 0xff) shl 8) or (hello.nonce[1].toInt() and 0xff)
            return decode(value)
        }

        /**
         * The tag this build sends: null while the switch is off, and for a build that is not a split
         * release (a debug or non-split build has no release number).
         */
        fun forBuild(versionCode: Long, signedWithReleaseKey: Boolean, enabled: Boolean): BuildTag? {
            if (!enabled) return null
            val release = ReleaseNumber.fromVersionCode(versionCode) ?: return null
            return BuildTag(release, signedWithReleaseKey)
        }
    }
}

/**
 * Process-wide hooks of the build tag, set by MainActivity from the "Tell me about newer versions
 * nearby" switch. With [local] null (the default, and every benchmark launch without
 * `test_newer_versions_nearby`) the hello bytes are exactly as before. [sink] receives the tag of
 * a peer whose link handshake verified on a link THIS phone opened (never from a stranger who connected
 * to our listener); it must not block.
 */
object LinkBuildTags {
    @Volatile
    var local: BuildTag? = null

    /**
     * This build's real tag regardless of the "Tell me about newer versions nearby" switch (null for a debug or non-split
     * build); never sent by itself, read only by [WifiHopGate.effectiveOwnTag].
     */
    @Volatile
    var build: BuildTag? = null

    @Volatile
    var sink: ((BuildTag) -> Unit)? = null

    internal fun report(tag: BuildTag) {
        runCatching { sink?.invoke(tag) }
    }
}
