# iPhone interoperability

**Design review required** for anything on the wire. Compact discovery (an iPhone-sized advertisement) is implemented
behind `test_ios_compact_discovery` (PROTOCOL.md "Experimental iPhone compact discovery"); the iOS client lives in the
sibling repository MeshLive/meshlive-ios. Done when an iPhone's call offer, viewer request and stream offer are found
and connected by every reference phone with the switch on, in both setup roles, with `scan_stats` counters and evidence
of whether each phone filters the masked UUID in its controller or falls back to host filtering (AOSP
`ScanManager.shouldUseAllPassFilter` moves a whole scanner to all-pass when it has more filters than free controller
slots; the switch doubles each scanner's slot use).

Optional H.264 is now specified together with HEVC in Design HC (draft 2026-10-27, not reviewed, not built): `BTV3`
codec 4, mask bit 2, `capabilitySummary` bit 2 and compact codec bit 56 (open question Q-HC2 proposes moving bit 56
to HEVC). Bit 7 (prefer VP9) still wins; the "prefer H.264" bit 6 is not used, and Design HC adds end-to-end bit 4
("my AV1 decoding is software").
