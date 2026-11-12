package com.example.bluetoothtest

/** Where a hop's records are written: the raw Bluetooth writer, or the hop's CarrierSet once a Wi-Fi attempt is feasible. */
interface HopRecordSink : RecordSink {                      // [API] A3's RecordSink; no second declaration of write
    /** Same contract as [RecordSink.write]: returns the wire bytes to account, 0 when the record was dropped. */
    override fun write(type: SecureRecordType, payload: ByteArray): Int
    fun flush()
}

/** Where a hop's records are read from. Never returns a `BWF1` record: the hop consumes those itself. */
interface HopRecordSource {
    var maxCiphertextBytes: Int
    fun read(): SecureRecord
}
