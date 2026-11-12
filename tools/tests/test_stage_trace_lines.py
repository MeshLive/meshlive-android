"""Offline regressions: the v2 batched stage-trace line reads as exactly the v1 records, and fails the way v1 does."""
import json
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stage_trace_lines as lines

LOGCAT = "11-11 10:00:00.000 17082 17139 I BluetoothVideo: "
BASE = {"session", "scope", "role", "direction", "pid", "clock", "sequence", "stage", "pts_us", "at_ns", "bytes", "final"}


def record(sequence, stage, role="video_decoder", pts=1000, at=5000, size=0, final=False, **extra):
    direction = "outgoing" if role in ("video_encoder", "broadcast_transport") or stage in (
        "send_enqueue", "write_begin", "write_end") else "incoming"
    return {"session": "s", "scope": "99", "role": role, "direction": direction, "pid": 7, "clock": "System.nanoTime",
            "sequence": sequence, "stage": stage, "pts_us": pts, "at_ns": at, "bytes": size, "final": final, **extra}


def v1_line(row):
    return LOGCAT + lines.V1 + json.dumps(row, separators=(",", ":"))


def v2_line(rows):
    head = {key: rows[0][key] for key in ("session", "scope", "role", "pid", "clock")}
    body = [[r["sequence"], r["stage"], r["pts_us"], r["at_ns"], r["bytes"], int(r["final"]),
             {k: v for k, v in r.items() if k not in BASE} or None] for r in rows]
    return LOGCAT + lines.V2 + json.dumps({**head, "rows": body}, separators=(",", ":"))


class StageTraceLinesTest(unittest.TestCase):
    def rows(self, role="video_decoder", stages=("decoder_input", "decoder_output", "release")):
        rows = [record(n + 1, stage, role=role, pts=n * 10, at=100 + n, size=n % 3) for n, stage in enumerate(stages)]
        rows.append(record(len(rows) + 1, "final", role=role, pts=0, at=999, final=True))
        return rows

    @staticmethod
    def parse(text):
        return [r for line in text.splitlines() for r in lines.stage_records(line)]

    def test_a_v2_batch_gives_the_same_records_as_the_equivalent_v1_lines(self):
        for role, stages in (("video_decoder", ("decoder_input", "release")),
                             ("video_encoder", ("raw_capture", "encode_output")),
                             ("broadcast_transport", ("send_enqueue", "write_end")),
                             ("call_transport", ("send_enqueue", "receive", "write_begin"))):
            rows = self.rows(role, stages)
            rows[0]["q_idx"] = 87
            rows[0]["frame_type"] = "key"
            self.assertEqual(self.parse("\n".join(v1_line(r) for r in rows)), self.parse(v2_line(rows)), role)

    def test_direction_is_derived_per_row_so_a_mixed_role_batch_keeps_each_stage_direction(self):
        rows = self.rows("call_transport", ("send_enqueue", "receive", "write_end", "decoder_input"))
        got = {r["stage"]: r["direction"] for r in self.parse(v2_line(rows))}
        self.assertEqual({"send_enqueue": "outgoing", "receive": "incoming", "write_end": "outgoing",
                          "decoder_input": "incoming", "final": "incoming"}, got)

    def test_extra_fields_never_override_base_fields(self):
        text = v2_line(self.rows()).replace('"rows":[[1,"decoder_input",0,100,0,0,null]',
                                            '"rows":[[1,"decoder_input",0,100,0,0,{"bytes":9,"depth":3}]')
        first = self.parse(text)[0]
        self.assertEqual((0, 3), (first["bytes"], first["depth"]))

    def test_lines_without_a_stage_trace_yield_nothing(self):
        self.assertEqual([], lines.stage_records(LOGCAT + "BENCHMARK lifecycle v1 {}"))
        self.assertEqual([], lines.stage_records("unrelated"))

    def test_broken_lines_raise_so_callers_can_ignore_them(self):
        good = v2_line(self.rows())
        head = LOGCAT + lines.V2 + '{"session":"s","scope":"1","role":"r","pid":1,"clock":"c",'
        for bad in (good[:-12], LOGCAT + lines.V2 + "{broken", LOGCAT + lines.V1 + "{broken",
                    LOGCAT + lines.V2 + '{"session":"s","rows":[]}',
                    head + '"rows":[[1,"a"]]}', head + '"rows":[[1,"a",0,0,0,true,null]]}',
                    head + '"rows":[[1,"a",0,0,0,0,5]]}'):
            with self.assertRaises(ValueError, msg=bad):
                lines.stage_records(bad)


if __name__ == "__main__":
    unittest.main()
