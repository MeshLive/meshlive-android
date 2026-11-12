"""Versioned acceptance profiles: which families exist and which thresholds apply.

device-run-v3 is the original engineering profile and stays the default; its
numbers live in score_device_run.DEFAULT_THRESHOLDS and are never edited.
device-run-v4-user judges impaired and over-the-link cases by user-centred
measurements (media age, longest stall, frozen-time share, audio continuity,
effective-profile cadence). Fidelity (VMAF/SSIM/PSNR), the visible-FPS fraction
and encoder FPS are reported as DIAGNOSTIC rows that never decide a verdict.
A result keeps the profile version it was scored with; nothing is re-labelled.
"""

V3 = "device-run-v3"
V4 = "device-run-v4-user"

FAMILIES_V3 = ("picture", "smoothness", "connection", "transport", "lifecycle",
               "topology", "camera", "acoustic", "physical_latency", "endurance")
# Task success and speech comprehension have no human or acoustic adapter yet; they stay UNMEASURED.
USER_FAMILIES = ("media_age", "task_success", "speech_comprehension")
FAMILIES_V4 = FAMILIES_V3 + USER_FAMILIES
KNOWN_FAMILIES = FAMILIES_V4
SWEEP_FAMILIES = {V3: ("picture", "smoothness", "connection", "transport"),
                  V4: ("smoothness", "connection", "transport")}
FAMILY_REASONS = {
    "media_age": "No finalized media-trace media-age measurement with measured clock anchors (queue ages are proxies only)",
    "acoustic": "No accepted-PCM speech-gap or acoustic-quality adapter joined; audio drop counters (transport) are only a continuity proxy",
    "task_success": "No human task-success adapter exists",
    "speech_comprehension": "No human or acoustic speech-comprehension adapter exists",
}

# Overlay on score_device_run.DEFAULT_THRESHOLDS. None means the target is not decided yet:
# the value is still measured and shown, and the gate stays UNMEASURED until a target is set.
USER_THRESHOLDS = {
    "version": V4,
    "max_stall_ms_call": 500,
    "max_stall_ms_stream": 1000,
    # Q-STARTUP-GATE (owner, 2026-11-06): the 3 s join gate measures selection-to-first-surface; launch-to-first-surface
    # (cold start plus browsing, about 5 s) is a reported non-gating metric. device-run-v3 keeps the launch basis.
    "join_gate_basis": "selection",
    "max_frozen_time_share_percent": 1.0,
    # Cadence (Q6, 2026-10-14): a clean report interval is "below" when its visible FPS is under this fraction of the
    # FPS in force; the gate passes when the below intervals are at most the share of scored time. The slowest-window
    # rule (every interval at or above the fraction) stays computable as the labelled legacy comparison field.
    "effective_fps_fraction": 0.95,
    "effective_fps_below_share_percent": 5.0,
    # Provisional (2026-10-09), matching the bounded media-age experiment's budgets; revisit
    # with A/B and acoustic evidence (DECISIONS "Product direction").
    "max_media_age_p95_ms_call": 400,
    "max_media_age_p95_ms_stream": 2000,
}
# Healthy-link regression guards and strict loss counters decide only on a healthy link.
HEALTHY_ONLY_GATES = frozenset({
    "unexpected_freeze_events", "surface_p95_ms", "surface_p99_ms",
    "decoder_drops", "sender_drops", "forwarding_drops", "camera_input_drops", "raw_input_drops",
})
# Reported with their measured verdict, but never decide in the user-centred profile.
DIAGNOSTIC_GATES = frozenset({"visible_fps", "encoder_fps"})
FIDELITY_METRICS = frozenset({"vmaf", "ssim", "psnr_db", "reference_validity"})


def families_for(version):
    if version in (None, V3):
        return FAMILIES_V3
    if version == V4:
        return FAMILIES_V4
    raise ValueError(f"Unknown acceptance profile: {version}")


def sweep_families_for(version):
    families_for(version)  # validates the version
    return SWEEP_FAMILIES[version or V3]


def link_class(manifest):
    """('healthy'|'impaired', source). Never inferred from results; undeclared means healthy."""
    manifest = manifest or {}
    declared = (manifest.get("acceptance") or {}).get("link_class")
    if declared in ("healthy", "impaired"):
        return declared, "declared"
    scenario = manifest.get("scenario") or {}
    if scenario.get("media_impairment") or scenario.get("media_impairments"):
        return "impaired", "media_impairment"
    return "healthy", "default"
