"""Parse the app's DEBUG stage-trace logcat lines into per-record dicts.

Two formats carry the same records and fields; both come out as the same dicts (v1 key order):

  v1  `BENCHMARK stage v1 {"session":..,"scope":..,"role":..,"direction":..,"pid":..,"clock":..,"sequence":..,
       "stage":..,"pts_us":..,"at_ns":..,"bytes":..,"final":bool,<extra fields>}`   one line per record
  v2  `BENCHMARK stage v2 {"session":..,"scope":..,"role":..,"pid":..,"clock":..,"rows":[ROW,...]}`   one line per batch
       ROW = [sequence, stage, pts_us, at_ns, bytes, final(0|1), extra-object-or-null]

v2 omits `direction`; it is derived exactly as the v1 writer computed it (DebugStageTrace/StageTraceBatcher.direction):
outgoing for role video_encoder or broadcast_transport, or stage send_enqueue/write_begin/write_end; else incoming.
Extra fields never override a base field. A malformed line raises ValueError (a truncated v2 line loses its whole
batch; the per-(scope, role) sequence numbers then show the gap).
"""
import json

V1 = "BENCHMARK stage v1 "
V2 = "BENCHMARK stage v2 "
OUTGOING_ROLES = {"video_encoder", "broadcast_transport"}
OUTGOING_STAGES = {"send_enqueue", "write_begin", "write_end"}
HEADER = ("session", "scope", "role", "pid", "clock")
ROW_COLUMNS = ("sequence", "stage", "pts_us", "at_ns", "bytes", "final", "extra")


def direction(role, stage):
    return "outgoing" if role in OUTGOING_ROLES or stage in OUTGOING_STAGES else "incoming"


def _payload(text):
    start = text.find("{")
    if start < 0:
        raise ValueError("stage line has no JSON payload")
    value, _ = json.JSONDecoder().raw_decode(text, start)
    if not isinstance(value, dict):
        raise ValueError("stage payload must be an object")
    return value


def stage_records(line):
    """Records of one logcat line: [] when it carries no stage trace; ValueError when it does but is malformed."""
    for marker, version in ((V1, 1), (V2, 2)):
        index = line.find(marker)
        if index >= 0:
            break
    else:
        return []
    body = _payload(line[index + len(marker):])
    if version == 1:
        return [body]
    if any(field not in body for field in HEADER) or not isinstance(body.get("rows"), list):
        raise ValueError("stage v2 line lacks its header fields or rows")
    records = []
    for row in body["rows"]:
        if not isinstance(row, list) or len(row) != len(ROW_COLUMNS):
            raise ValueError("stage v2 row must be [sequence, stage, pts_us, at_ns, bytes, final, extra]")
        sequence, stage, pts_us, at_ns, size, final, extra = row
        if final not in (0, 1) or isinstance(final, bool):
            raise ValueError("stage v2 final must be 0 or 1")
        if extra is not None and not isinstance(extra, dict):
            raise ValueError("stage v2 extra must be an object or null")
        record = {"session": body["session"], "scope": body["scope"], "role": body["role"],
                  "direction": direction(body["role"], stage), "pid": body["pid"], "clock": body["clock"],
                  "sequence": sequence, "stage": stage, "pts_us": pts_us, "at_ns": at_ns, "bytes": size,
                  "final": bool(final)}
        for key, value in (extra or {}).items():
            record.setdefault(key, value)
        records.append(record)
    return records
