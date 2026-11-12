# Debug connectivity is separate from media

A three-phone run (2026-10-01) recorded an Android BSSID-change shutdown of wireless debugging before the TLS errors: a network-change disable and `adb_wifi_enabled` 1 -> 0 set by Android. The same SSID/band did not prove an unchanged BSSID; the initiating reassociation cause is unproved. The debug transport environment is separate from Bluetooth media, which continued.
