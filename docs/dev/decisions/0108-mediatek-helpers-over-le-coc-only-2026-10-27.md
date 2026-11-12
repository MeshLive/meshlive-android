# MediaTek helpers over LE CoC only (2026-10-27)

Relayed calls through a MediaTek phone as GATT middle failed with record desyncs: the A15s (MT6765, Android 10) held 0 of
8 in every arm (control, a shallow stack queue `test_gatt_byte_window=3072` with 120 kbps, 244-byte payloads), and the
next phone's HCI log showed the chunks contiguous but single bytes changed inside queued notifications. The same phone
never desynced on single-link calls (228 direct GATT calls); the Xiaomi Redmi 12 (also MediaTek) failed 3 of 10 as GATT
middle; non-MediaTek middles 0 of 72; the A15s as a CoC middle held 7 of 8. Android 10 reports a notification as sent
when it is queued in the stack (AOSP btif_gatt_server.cc), so the app cannot see the corruption before it leaves.
Options were (A) a device-family entry keeping MediaTek helper duty on LE CoC, (B) a protocol fix, per-chunk integrity
with a GR1 re-request (a wire change that needs a design and review), (C) nothing, (D) only (B). The choice was (A) now
and (B) later (PLAN). Entry `mediatek-call-relay-gatt-helper-off`, low confidence, off with
`test_device_policy_off=mediatek-call-relay-gatt-helper-off`.
