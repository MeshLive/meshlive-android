"""Offline stage, lifecycle and clock regressions; no phone operations."""
from contextlib import redirect_stderr, redirect_stdout
import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import score_pipeline_trace as pipeline


class PipelineTraceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="pipeline-trace-")
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        self.identity = {"run_id": "run-1", "phone": "a", "session": "session-1", "pid": 17,
                         "clock": "System.nanoTime", "source": "a", "direction": "outgoing", "stream_generation": 1}

    def pointer(self, name):
        return {"path": name, "sha256": hashlib.sha256((self.folder / name).read_bytes()).hexdigest()}

    def document(self, name, value):
        (self.folder / name).write_text(json.dumps(value), encoding="utf-8")
        return self.pointer(name)

    def row(self, stage, pts, at, sequence, **extra):
        return {**self.identity, "scope": "scope-1", "sequence": sequence, "stage": stage, "pts_us": pts,
                "at_ns": at, "bytes": 4096 if stage == "encode_output" else 0,
                "final": stage == "final", **extra}

    def source_rows(self, duration=1_000_000_000, stall=None):
        rows = []
        for index in range(round(duration * 30 / 1e9)):
            at = index * 1_000_000_000 // 30
            rows.append(self.row("encode_input", at // 1000, at, len(rows) + 1))
            delay = 40_000_000 if index == stall else 5_000_000
            rows.append(self.row("encode_output", at // 1000, at + delay, len(rows) + 1))
        rows.append(self.row("final", 0, duration + 100_000_000, len(rows) + 1))
        return rows

    def spec(self, rows=None, duration=1_000_000_000):
        if rows is None:
            rows = self.source_rows(duration)
        return {"schema": "benchmark-pipeline-observation-v1", "traces": [{
            **self.identity, **self.document("trace.json", {"schema": "benchmark-stage-events-v1", "events": rows}),
            "format": "json", "capture_start_ns": 0, "capture_end_ns": duration + 100_000_000}],
            "plans": [{**self.identity, "start_ns": 0, "end_ns": duration, "fps": 30, "bitrate_kbps": 1000,
                       "stages": ["encode_output"]}],
            "acceptance": {"fps_fraction": .95, "interval_p95_periods": 1.25, "interval_p99_periods": 2,
                           "freeze_threshold_ms": 250, "max_freezes": 0, "offered_load_fraction": .90,
                           "max_missing_frames": 0, "span_max_ms": {"encode_input->encode_output": 20}}}

    def analyze(self, spec):
        return pipeline.analyze(spec, self.folder)

    @staticmethod
    def gate(result, name):
        return next(row for row in result["measurements"] if row["name"] == name)

    def test_exact_40ms_encode_stall_and_full_plan_load(self):
        result = self.analyze(self.spec(self.source_rows(stall=10)))
        spans = [row for row in result["diagnostics"]["local_spans"] if row["duration_ns"] == 40_000_000]
        self.assertEqual(1, len(spans))
        self.assertEqual("encode_input", spans[0]["from_stage"])
        self.assertEqual("encode_output", spans[0]["to_stage"])
        self.assertEqual("FAIL", self.gate(result, "encode_input_to_encode_output_max_ms")["status"])
        self.assertEqual(40, self.gate(result, "encode_input_to_encode_output_max_ms")["value"])
        self.assertEqual("PASS", self.gate(result, "encoded_payload_load_fraction")["status"])
        self.assertEqual("UNMEASURED", self.gate(result, "physical_pipeline_latency")["status"])

    def test_dropped_sequence_and_nonfinalized_scope_prevent_pass(self):
        for defect in ("gap", "nonfinal", "afterfinal", "sequence_backtrack"):
            with self.subTest(defect=defect):
                rows = self.source_rows()
                if defect == "gap":
                    del rows[20]
                elif defect == "nonfinal":
                    rows.pop()
                elif defect == "afterfinal":
                    rows.append(self.row("encode_output", 1_100_000, 1_100_000_000, rows[-1]["sequence"] + 1))
                else:
                    rows[20], rows[21] = rows[21], rows[20]
                result = self.analyze(self.spec(rows))
                self.assertEqual("INCOMPLETE", result["diagnostics"]["scopes"][0]["status"])
                self.assertEqual("UNMEASURED", self.gate(result, "pipeline_fps_fraction")["status"])
                self.assertEqual("UNMEASURED", self.gate(result, "encode_input_to_encode_output_max_ms")["status"])

    def test_repeated_pts_within_generation_remains_ambiguous(self):
        rows = self.source_rows()
        rows[3]["pts_us"] = rows[1]["pts_us"]
        result = self.analyze(self.spec(rows))
        self.assertTrue(result["diagnostics"]["ambiguous_stage_identities"])
        self.assertEqual([0], result["diagnostics"]["timing_windows"][0]["duplicate_pts_us"])
        self.assertEqual("UNMEASURED", self.gate(result, "pipeline_fps_fraction")["status"])

    def test_repeated_pts_across_declared_generations_are_distinct(self):
        first = [self.row("encode_input", 0, 0, 1), self.row("encode_output", 0, 40_000_000, 2), self.row("final", 0, 100_000_000, 3)]
        spec = self.spec(first)
        second = [self.row("encode_input", 0, 200_000_000, 1, stream_generation=2, scope="scope-2"),
                  self.row("encode_output", 0, 205_000_000, 2, stream_generation=2, scope="scope-2"),
                  self.row("final", 0, 300_000_000, 3, stream_generation=2, scope="scope-2")]
        spec["traces"].append({**self.identity, "stream_generation": 2, "format": "json",
                               **self.document("trace-2.json", {"schema": "benchmark-stage-events-v1", "events": second})})
        result = self.analyze(spec)
        spans = [row for row in result["diagnostics"]["local_spans"] if row["from_stage"] == "encode_input" and row["to_stage"] == "encode_output"]
        self.assertEqual([40_000_000, 5_000_000], [row["duration_ns"] for row in spans])
        self.assertFalse(result["diagnostics"]["ambiguous_stage_identities"])

    def test_one_trace_cannot_be_relabelled_as_a_second_generation(self):
        spec = self.spec()
        spec["traces"].append({**spec["traces"][0], "stream_generation": 2})
        with self.assertRaisesRegex(ValueError, "disjoint scope"):
            self.analyze(spec)

    def test_clock_contradiction_rejected_and_cross_phone_spans_not_joined(self):
        spec = self.spec([self.row("encode_input", 0, 0, 1, clock="different-clock")])
        with self.assertRaisesRegex(ValueError, "clock contradicts"):
            self.analyze(spec)
        spec = self.spec([self.row("encode_input", 0, 0, 1), self.row("final", 0, 100_000_000, 2)])
        other = [self.row("encode_output", 0, 40_000_000, 1, phone="b", pid=19), self.row("final", 0, 100_000_000, 2, phone="b", pid=19)]
        spec["traces"].append({**self.identity, "phone": "b", "pid": 19, "format": "json",
                               **self.document("other.json", {"schema": "benchmark-stage-events-v1", "events": other})})
        self.assertFalse(any(row["status"] == "MEASURED" for row in self.analyze(spec)["diagnostics"]["local_spans"]))

    def test_unfinished_frame_span_is_visible_and_fast_prefix_cannot_pass(self):
        rows = self.source_rows()
        rows[3]["stage"] = "send_enqueue"  # One encode_input never completes.
        result = self.analyze(self.spec(rows))
        missing = [row for row in result["diagnostics"]["local_spans"] if row["pts_us"] == rows[2]["pts_us"]
                   and row["from_stage"] == "encode_input" and row["to_stage"] == "encode_output"]
        self.assertEqual("UNMEASURED", missing[0]["status"])
        self.assertEqual("UNMEASURED", self.gate(result, "encode_input_to_encode_output_max_ms")["status"])

    def test_logged_sequence_backtracking_event_time_is_allowed(self):
        rows = [self.row("decoder_output", 55, 100_000_000, 1, direction="incoming"),
                self.row("release", 55, 110_000_000, 2, direction="incoming"),
                self.row("surface_ack", 55, 105_000_000, 3, direction="incoming"),
                self.row("texture_notification", 50_000, 90_000_000, 4, direction="incoming"),
                self.row("final", 0, 150_000_000, 5, direction="incoming")]
        spec = self.spec(rows)
        spec["traces"][0]["direction"] = "incoming"
        spec["plans"] = []
        result = self.analyze(spec)
        self.assertEqual("COMPLETE", result["diagnostics"]["scopes"][0]["status"])
        span = next(row for row in result["diagnostics"]["local_spans"] if row["from_stage"] == "release")
        self.assertEqual(-5_000_000, span["duration_ns"])
        self.assertEqual("AMBIGUOUS", span["status"])

    def test_full_tail_and_empty_partial_payload_bins_stay_visible(self):
        rows = [self.row("encode_output", 0, 100_000_000, 1), self.row("encode_output", 1, 2_100_000_000, 2), self.row("final", 0, 2_600_000_000, 3)]
        result = self.analyze(self.spec(rows, duration=2_500_000_000))
        window = result["diagnostics"]["timing_windows"][0]
        self.assertEqual(400_000_000, window["terminal_tail_ns"])
        self.assertIsNone(window["expected_frames"])
        bins = window["payload_bins"]["1000000000"]
        self.assertEqual([4096, 0, 4096], [row["bytes"] for row in bins])
        self.assertEqual([False, False, True], [row["partial"] for row in bins])
        self.assertEqual("FAIL", self.gate(result, "pipeline_freeze_gaps")["status"])
        self.assertEqual("FAIL", self.gate(result, "pipeline_fps_fraction")["status"])

    def test_expected_frame_ids_are_explicit_not_inferred_from_fps(self):
        spec = self.spec()
        spec["plans"][0]["expected_pts_us"] = [0, 12345]
        result = self.analyze(spec)
        self.assertEqual([12345], result["diagnostics"]["timing_windows"][0]["missing_pts_us"])
        self.assertEqual("FAIL", self.gate(result, "pipeline_missing_expected_frames")["status"])

    def test_missing_capture_bounds_or_final_before_end_are_unmeasured(self):
        for mode in ("bounds", "final"):
            spec = self.spec()
            if mode == "bounds":
                spec["traces"][0].pop("capture_start_ns")
            else:
                rows = self.source_rows()
                rows[-1]["at_ns"] = 900_000_000
                spec["traces"][0].update(self.document("trace.json", {"schema": "benchmark-stage-events-v1", "events": rows}))
            result = self.analyze(spec)
            self.assertEqual("UNMEASURED", self.gate(result, "pipeline_fps_fraction")["status"])

    def test_logcat_mixed_directions_share_complete_scope_without_duplicate_records(self):
        rows = [self.row("encode_input", 0, 0, 1), self.row("encode_output", 0, 40_000_000, 2),
                self.row("receive", 70, 45_000_000, 3, direction="incoming"), self.row("decoder_input", 70, 50_000_000, 4, direction="incoming"),
                self.row("final", 0, 100_000_000, 5)]
        path = self.folder / "trace.log"
        path.write_text("unrelated row\n" + "\n".join("10-04 x " + pipeline.PREFIX + json.dumps(row) for row in rows))
        spec = self.spec(rows)
        spec["traces"][0].update({**self.pointer("trace.log"), "format": "logcat"})
        spec["traces"].append({**spec["traces"][0], "direction": "incoming"})
        spec["plans"] = []
        result = self.analyze(spec)
        self.assertEqual(1, len(result["diagnostics"]["scopes"]))
        self.assertEqual("COMPLETE", result["diagnostics"]["scopes"][0]["status"])
        self.assertEqual(2, sum(row["status"] == "MEASURED" for row in result["diagnostics"]["local_spans"]))

    def test_v2_batched_logcat_scores_exactly_like_the_v1_lines(self):
        # Encoder-role rows are outgoing, decoder-role rows incoming: the reader derives direction from role/stage.
        rows = [self.row("encode_input", 0, 0, 1, role="video_encoder"),
                self.row("encode_output", 0, 40_000_000, 2, role="video_encoder", q_idx=87),
                self.row("receive", 70, 45_000_000, 3, role="video_decoder", direction="incoming"),
                self.row("decoder_input", 70, 50_000_000, 4, role="video_decoder", direction="incoming"),
                self.row("final", 0, 100_000_000, 5, role="video_encoder")]

        def v2(batch):
            head = {key: batch[0][key] for key in ("session", "pid", "clock", "role")}
            head["scope"] = "scope-1"
            body = [[r["sequence"], r["stage"], r["pts_us"], r["at_ns"], r["bytes"], int(r["final"]),
                     {"q_idx": r["q_idx"]} if "q_idx" in r else None] for r in batch]
            return "10-04 x BENCHMARK stage v2 " + json.dumps({**head, "rows": body})

        def analyze_log(text):
            (self.folder / "trace.log").write_text(text)
            spec = self.spec(rows)
            spec["traces"][0].update({**self.pointer("trace.log"), "format": "logcat"})
            spec["traces"].append({**spec["traces"][0], "direction": "incoming"})
            spec["plans"] = []
            return self.analyze(spec)["diagnostics"]

        v1 = analyze_log("\n".join("10-04 x " + pipeline.PREFIX + json.dumps(r) for r in rows))
        v2_text = "unrelated\n" + "\n".join(v2(batch) for batch in (rows[0:2], rows[2:4], rows[4:5]))
        batched = analyze_log(v2_text)
        self.assertEqual(2, sum(row["status"] == "MEASURED" for row in batched["local_spans"]))
        self.assertEqual("COMPLETE", batched["scopes"][0]["status"])
        self.assertEqual(v1["local_spans"], batched["local_spans"])
        self.assertEqual(v1["scopes"], batched["scopes"])

    def test_a_truncated_v2_line_is_not_scored_silently(self):
        row = [1, "encode_input", 0, 0, 0, 0, None]
        text = "10-04 x BENCHMARK stage v2 " + json.dumps({"session": "session-1", "scope": "scope-1", "role": "video_encoder",
                                                           "pid": 17, "clock": "System.nanoTime", "rows": [row]})[:-8]
        (self.folder / "trace.log").write_text(text)
        spec = self.spec()
        spec["traces"][0].update({**self.pointer("trace.log"), "format": "logcat"})
        with self.assertRaises(ValueError):
            self.analyze(spec)

    def lifecycle_spec(self, missing=False):
        spec = self.spec()
        names = ("fault", "command", "ack", "first_surface")
        rows = [{**self.identity, "attempt": "nonce-7", "action": "rejoin", "kind": kind,
                 "at_ns": at, "authenticated": kind == "ack"}
                for kind, at in zip(names, (0, 1_000_000_000, 2_000_000_000, 7_000_000_000)) if not missing or kind != "ack"]
        spec["lifecycle_events"] = self.document("lifecycle.json", {"schema": "benchmark-lifecycle-events-v1", "events": rows})
        spec["lifecycle_plans"] = [{**self.identity, "attempt": "nonce-7", "action": "rejoin", "start_ns": 0,
                                    "end_ns": 10_000_000_000, "requires_fault": True, "requires_surface": True}]
        spec["acceptance"].update({"recovery_ms": 5000, "command_ack_ms": 1500})
        return spec

    def test_recovery_counts_fault_and_discovery_gap_not_only_handshake(self):
        result = self.analyze(self.lifecycle_spec())
        self.assertEqual(7000, result["diagnostics"]["lifecycle"][0]["fault_or_command_to_first_surface_ms"])
        self.assertEqual("FAIL", self.gate(result, "full_fault_recovery_ms")["status"])
        self.assertEqual("PASS", self.gate(result, "lifecycle_command_ack_ms")["status"])

    def test_lifecycle_missing_auth_ack_and_completion_tail_stay_unmeasured(self):
        result = self.analyze(self.lifecycle_spec(missing=True))
        outcome = result["diagnostics"]["lifecycle"][0]
        self.assertEqual("UNMEASURED", outcome["status"])
        self.assertEqual(3000, outcome["remaining_tail_ms"])
        self.assertEqual("UNMEASURED", self.gate(result, "full_fault_recovery_ms")["status"])
        spec = self.lifecycle_spec()
        doc = json.loads((self.folder / "lifecycle.json").read_text())
        doc["events"][2]["authenticated"] = False
        spec["lifecycle_events"] = self.document("lifecycle.json", doc)
        self.assertEqual("UNMEASURED", self.analyze(spec)["diagnostics"]["lifecycle"][0]["status"])

    def overlap_spec(self, anchors=True):
        spec = self.spec()
        rows = [{**self.identity, "lane": "left", "participants": ["a", "c"], "start_ns": 0, "end_ns": 2_000_000_000,
                 "basis": "observed_active_media_interval"},
                {**self.identity, "phone": "b", "pid": 19, "lane": "right", "participants": ["b", "d"],
                 "start_ns": 1_000_000_000, "end_ns": 3_000_000_000, "basis": "observed_active_media_interval"}]
        spec["active_media_intervals"] = self.document("overlap.json", {"schema": "benchmark-active-media-intervals-v1", "intervals": rows})
        if anchors:
            values = [{"run_id": "run-1", "phone": phone, "pid": pid, "clock": "System.nanoTime", "common_clock": "independent-clock",
                       "basis": "measured_pairs", "method": "independent synchronized observation",
                       "pairs": [{"local_ns": 0, "common_ns": 100_000_000_000, "uncertainty_ns": 1000},
                                 {"local_ns": 4_000_000_000, "common_ns": 104_000_000_000, "uncertainty_ns": 1000}]}
                      for phone, pid in (("a", 17), ("b", 19))]
            spec["clock_anchors"] = self.document("anchors.json", {"schema": "benchmark-clock-anchors-v1", "anchors": values})
        spec["acceptance"]["minimum_overlap_ms"] = 800
        return spec

    def test_disjoint_overlap_requires_measured_clock_anchors(self):
        result = self.analyze(self.overlap_spec(anchors=False))
        self.assertEqual("UNMEASURED", result["diagnostics"]["actual_media_overlap"]["status"])
        result = self.analyze(self.overlap_spec())
        outcome = result["diagnostics"]["actual_media_overlap"]
        self.assertEqual("MEASURED", outcome["status"])
        self.assertAlmostEqual(999.998, outcome["pairs"][0]["guaranteed_duration_ms"], places=6)
        self.assertEqual("PASS", self.gate(result, "actual_media_overlap_ms")["status"])

    def test_clock_anchor_cannot_be_reused_for_another_run_or_extrapolated(self):
        for mode in ("run", "extrapolation"):
            spec = self.overlap_spec()
            doc = json.loads((self.folder / "anchors.json").read_text())
            if mode == "run":
                doc["anchors"][1]["run_id"] = "different-run"
            else:
                doc["anchors"][1]["pairs"][-1]["local_ns"] = 2_000_000_000
                doc["anchors"][1]["pairs"][-1]["common_ns"] = 102_000_000_000
            spec["clock_anchors"] = self.document("anchors.json", doc)
            self.assertEqual("UNMEASURED", self.analyze(spec)["diagnostics"]["actual_media_overlap"]["status"])

    def test_explicit_inactive_gaps_are_not_filled_from_first_last_frames(self):
        spec = self.overlap_spec()
        doc = json.loads((self.folder / "overlap.json").read_text())
        left, right = doc["intervals"]
        doc["intervals"] = [{**left, "end_ns": 1_000_000_000},
                            {**left, "start_ns": 3_000_000_000, "end_ns": 4_000_000_000},
                            {**right, "start_ns": 1_500_000_000, "end_ns": 2_500_000_000}]
        spec["active_media_intervals"] = self.document("overlap.json", doc)
        result = self.analyze(spec)
        self.assertEqual(0, result["diagnostics"]["actual_media_overlap"]["pairs"][0]["guaranteed_duration_ms"])
        self.assertEqual("FAIL", self.gate(result, "actual_media_overlap_ms")["status"])

    def test_first_last_inference_and_shared_phone_lanes_do_not_pass_overlap(self):
        for mode in ("inferred", "shared"):
            spec = self.overlap_spec()
            doc = json.loads((self.folder / "overlap.json").read_text())
            if mode == "inferred":
                doc["intervals"][0]["basis"] = "first_and_last_frame"
            else:
                doc["intervals"][1]["participants"].append("a")
            spec["active_media_intervals"] = self.document("overlap.json", doc)
            self.assertEqual("UNMEASURED", self.analyze(spec)["diagnostics"]["actual_media_overlap"]["status"])

    def test_endurance_needs_full_resource_and_timing_coverage(self):
        spec = self.spec(duration=3_000_000_000)
        rows = [{**self.identity, "at_ns": at, "cpu_percent": 15} for at in range(0, 3_000_000_001, 1_000_000_000)]
        spec["resources"] = self.document("resources.json", {"schema": "benchmark-resource-events-v1", "finalized": True, "events": rows})
        spec["resource_plans"] = [{**self.identity, "start_ns": 0, "end_ns": 3_000_000_000,
                                   "maximum_gap_ns": 1_100_000_000, "minimum_duration_seconds": 1, "required_metrics": ["cpu_percent"]}]
        result = self.analyze(spec)
        self.assertEqual("PASS", self.gate(result, "endurance_observation_duration_seconds")["status"])
        self.assertEqual("UNMEASURED", self.gate(result, "endurance_power_thermal_quality")["status"])
        rows.pop()
        spec["resources"] = self.document("resources.json", {"schema": "benchmark-resource-events-v1", "finalized": True, "events": rows})
        self.assertEqual("UNMEASURED", self.gate(self.analyze(spec), "endurance_observation_duration_seconds")["status"])

    def test_resource_timestamps_without_required_values_cannot_pass(self):
        spec = self.spec(duration=3_000_000_000)
        rows = [{**self.identity, "at_ns": at} for at in range(0, 3_000_000_001, 1_000_000_000)]
        spec["resources"] = self.document("resources.json", {"schema": "benchmark-resource-events-v1", "finalized": True, "events": rows})
        spec["resource_plans"] = [{**self.identity, "start_ns": 0, "end_ns": 3_000_000_000,
                                   "maximum_gap_ns": 1_100_000_000, "minimum_duration_seconds": 1, "required_metrics": ["cpu_percent"]}]
        self.assertEqual("UNMEASURED", self.gate(self.analyze(spec), "endurance_observation_duration_seconds")["status"])

    def test_csv_is_exact_and_corrupt_hash_rejected(self):
        rows = self.source_rows()
        path = self.folder / "trace.csv"
        with path.open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows([{**row, "final": str(row["final"]).lower()} for row in rows])
        spec = self.spec(rows)
        spec["traces"][0].update({**self.pointer("trace.csv"), "format": "csv"})
        self.assertEqual("COMPLETE", self.analyze(spec)["diagnostics"]["scopes"][0]["status"])
        spec["traces"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.analyze(spec)

    def test_cli_preserves_input_and_requires_fresh_output(self):
        spec = self.spec()
        self.document("spec.json", spec)
        original = (self.folder / "trace.json").read_bytes()
        target = self.folder / "output.json"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, pipeline.main(["--spec", str(self.folder / "spec.json"), "--output", str(target)]))
        self.assertEqual(original, (self.folder / "trace.json").read_bytes())
        saved = target.read_bytes()
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            pipeline.main(["--spec", str(self.folder / "spec.json"), "--output", str(target)])
        self.assertEqual(saved, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
