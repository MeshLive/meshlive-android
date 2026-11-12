# Design PA: the announcement in the primary advert (no scan-response dependency, 2026-10-20; design only)

**Status: design for review; no code until the coordinator and the user approve it.** Wire draft: PROTOCOL "Draft: announcement in the primary advert (Design PA)".

**Problem (F11).** Every app advert today is a scannable legacy advert. The primary PDU (`ADV_IND`, at most 31 bytes, BT Core Vol 6 Part B 2.3.1) carries only the 128-bit service UUID `c8d5a101-…` (18 bytes) plus, before Android 14, the Flags AD (3 bytes). The 22-byte announcement (role, RDI, flags, token, cost) sits in the scan response, so a scanner learns who is advertising only after a `SCAN_REQ`/`SCAN_RSP` exchange (Vol 6 Part B 4.4.3). Android delivers a scannable advert to the app only once its scan response has been merged, so a scanner that never completes that exchange receives nothing at all: no reject, `withoutAnnouncement=0`.
- On build 8143c157 the Redmi 12 (HyperOS 15, MT6768), scanning LE 1M only, still got no complete A15s record in 30 s, although the A15s advertised at 100 ms and high power. The S22 completed the same advert in 2-3 s.
- Scanners also back off their scan requests under contention (Vol 6 Part B 4.4.3.2), so in a crowd the delay grows for everyone.
- The HCI snoop capture the coordinator is running will show whether the Redmi ever receives the A15s's `SCAN_RSP`.

**Rule (option A, recommended): swap the two halves.**
- *Primary PDU:* Flags (3 bytes, only where Android adds them) + manufacturer data, company `0xFFFF`, with the unchanged 22-byte announcement (2 + 2 + 22 = 26 bytes). That is 29 of 31 bytes, or 26 on Android 14+ non-discoverable sets.
- *Scan response:* the 128-bit service UUID (18 of 31 bytes).
- No byte of the announcement changes. Its version, RDI, flags and route token are exactly today's, so every decoder already reads it. A scanner that receives only the primary PDU now has the whole announcement, and the scan response becomes optional.

**Scanning.** New scanners keep today's service-UUID filter and add a second `ScanFilter` on manufacturer data, company `0xFFFF`, data prefix = the announcement's version byte (one filter per version in use: 2, 5, 8; version 3 title pages and 4 reach pages are recognised by their own magic). Filters are ORed, still one scan start (no quota cost); the host re-checks every field as today. Company `0xFFFF` is the Bluetooth SIG test ID, so other devices may use it; the version-byte prefix and the strict 22-byte decode keep the false-match rate low. A false match costs one host decode.

**Compatibility.** This is the part that needs phone evidence before any default change.
- *Old Android builds* filter on the service UUID only. Whether the platform offload matches a UUID that moved to the scan response is per controller (AOSP's APCF filters are documented to apply to the advertising data; the host merge covers the scan response; the vendor offload is UNVERIFIED). If an old scanner cannot match, it loses phones that advertise the new layout.
- So the advertiser switches layout only after **both** of these: (1) a phone test shows old builds still find new-layout adverts on every required phone; (2) a release in which new builds scan for both layouts has been out for one cycle.
- Until then the swap is Experimental and off (`test_primary_announcement`). Scanning for both layouts (the added filter) is harmless and can ship first.
- *iPhone:* iOS reads manufacturer data in the primary advert of a foreground scan, and its own advert (the compact UUID) is unchanged.
- *Windows:* it reads both.
- *Design QW and compact v2* are unaffected.

**Alternatives.**
- *(B) Compact v2 UUID in the primary:* the 16-byte UUID AD (18 bytes) fits, but then the service UUID has no room in the primary, the compact form has no route recovery or bitrate detail, and old builds lose the phone unless "Find iPhones" is on.
- *(C) Extended (non-legacy) advertising carrying everything in one PDU:* our own scans now ask for legacy adverts only, old builds cannot receive it, and it doubles the sets (two layouts on air).
- *(D) Keep the layout and retry harder (fresh scanners, longer searches):* that is shipped (F11 items 1-4) and did not reach the Redmi in 30 s.

**Privacy.** Unchanged in content: the same announcement, the same RDI, readable by anyone who can already send a scan request. Passive scanners (which never send a scan request) now see the announcement too, without leaving a trace on the advertiser. That is an exposure change: today a passive observer needs an active scan to read the RDI, and active scans are anonymous anyway (random scanner address). Stated, not mitigated.

**Tests (when approved).** JVM:
- byte layout and budget per API level;
- an old-layout and a new-layout advert decode to the same announcement;
- the filter set.

Phone:
- a new-layout A15s found by the Redmi 12 Meet scan (10/10);
- old build x new layout and new build x old layout on every required phone;
- crowd rate (sightings per address per second) old vs new layout.
