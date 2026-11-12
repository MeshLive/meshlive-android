# Bluetooth media protocol

For implementers and security reviewers: the normative wire formats and security rules. How the pieces fit together is explained in [architecture.md](../architecture.md). This reference defines the current authenticated application protocol over Classic RFCOMM, LE L2CAP CoC and LE GATT. All integers are network byte order. Plaintext compatibility and silent transport fallback are rejected.


## Reading order

1. **Identity and handshakes**: who a phone is, how two phones authenticate and encrypt a link, and the security boundaries.
   - [Security layers and participants](identity.md#security-layers-and-participants)
   - [Identity and key agreement](identity.md#identity-and-key-agreement)
   - [Identity verification](identity.md#identity-verification)
   - [Build tag in the link hello and update verification (updater, default on; wire change 2026-10-11)](identity.md#build-tag-in-the-link-hello-and-update-verification-updater-default-on-wire-change-2026-10-11)
   - [Experimental BMH3 identity-hiding link handshake (on by default for user sessions since 0.2.0-beta)](identity.md#experimental-bmh3-identity-hiding-link-handshake-on-by-default-for-user-sessions-since-020-beta)
   - [Emergency wipe](identity.md#emergency-wipe)
   - [Confidentiality and failure boundaries](identity.md#confidentiality-and-failure-boundaries)
   - [Verification](identity.md#verification)
2. **Links and framing**: the records carried on every hop, the GATT byte stream and the compact-header and video-codec drafts.
   - [Hop and endpoint records](links.md#hop-and-endpoint-records)
   - [GATT byte stream and draft lost-chunk recovery](links.md#gatt-byte-stream-and-draft-lost-chunk-recovery)
   - [Draft: compact headers and aggregation `CH1` (Design CH, draft 2026-10-27, not reviewed, not built)](links.md#draft-compact-headers-and-aggregation-ch1-design-ch-draft-2026-10-27-not-reviewed-not-built)
   - [Draft: HEVC and H.264 video codecs (Design HC, revision 3 after reviews 1 and 2, ready for implementation)](links.md#draft-hevc-and-h264-video-codecs-design-hc-revision-3-after-reviews-1-and-2-ready-for-implementation)
3. **Discovery and advertising**: what a phone broadcasts, how it is found, and the names it shows.
   - [Peer labels: handles, names, call subjects and stream titles](discovery.md#peer-labels-handles-names-call-subjects-and-stream-titles)
   - [Naming v2: generated names, label timing, name pages and verification codes (design 2026-10-12, not built)](discovery.md#naming-v2-generated-names-label-timing-name-pages-and-verification-codes-design-2026-10-12-not-built)
   - [Experimental iPhone compact discovery (on by default since 2026-10-17)](discovery.md#experimental-iphone-compact-discovery-on-by-default-since-2026-10-17)
   - [Experimental PC announcement (Find PCs, off by default; kinds 1 and 2 are a draft, not built)](discovery.md#experimental-pc-announcement-find-pcs-off-by-default-kinds-1-and-2-are-a-draft-not-built)
   - [Experimental rotating discovery IDs (on by default for user sessions since 0.2.0-beta)](discovery.md#experimental-rotating-discovery-ids-on-by-default-for-user-sessions-since-020-beta)
4. **Calls**: private-call signalling and in-call link renewal.
   - [Private-call signaling](calls.md#private-call-signaling)
   - [Experimental in-call link renewal (Design SR, on by default)](calls.md#experimental-in-call-link-renewal-design-sr-on-by-default)
5. **Streams**: audio-only live streams and the live media state.
   - [Experimental audio-only live streams (on by default since 2026-10-17)](streams.md#experimental-audio-only-live-streams-on-by-default-since-2026-10-17)
   - [Experimental live media state (off by default)](streams.md#experimental-live-media-state-off-by-default)
6. **Relays**: relayed calls, multi-hop discovery, liveness and route withdrawal.
   - [Experimental relayed calls and multi-hop discovery (off by default)](relays.md#experimental-relayed-calls-and-multi-hop-discovery-off-by-default)
   - [Lifecycle, liveness and route withdrawal](relays.md#lifecycle-liveness-and-route-withdrawal)
7. **Messaging**: store-and-forward messages and Messaging v2.
   - [Store-and-forward messages](messaging.md#store-and-forward-messages)
   - [Messaging v2: conversations, attachments, receipts, forward secrecy (phases 1a, 1b and 1d built 2026-10-12; on whenever Messages is on since 2026-10-21; phases 1c, 2 to 3 not built)](messaging.md#messaging-v2-conversations-attachments-receipts-forward-secrecy-phases-1a-1b-and-1d-built-2026-10-12-on-whenever-messages-is-on-since-2026-10-21-phases-1c-2-to-3-not-built)
8. **Experimental transports**: dual listening, the Classic address exchange draft and the Wi-Fi transport draft.
   - [Experimental dual listening (off by default)](experimental.md#experimental-dual-listening-off-by-default)
   - [Draft: Classic address exchange and media upgrade (design 2026-10-14, revised after five reviews, not implemented)](experimental.md#draft-classic-address-exchange-and-media-upgrade-design-2026-10-14-revised-after-five-reviews-not-implemented)
   - [Draft: Wi-Fi transport (Design WF revision 3, not built)](experimental.md#draft-wi-fi-transport-design-wf-revision-3-not-built)
