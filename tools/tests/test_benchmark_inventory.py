"""Offline inventory invariants: stable requests, directed coverage and honest gaps."""
import contextlib
import copy
import io
import itertools
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import generate_benchmark_inventory as inventory

PHONES = ["s24", "s22", "m31", "asus", "xiaomi", "a55"]
TRANS = ["classic", "ble", "gatt"]


def capability(alias):
    return {"schema_version": 1, "fingerprint": "fingerprint-" + alias,
            "node_prefix": f"{PHONES.index(alias) + 1:012x}",
            "decoders": ["vp9"], "playback": ["low", "balanced", "stable"],
            "transport_max_kbps": dict.fromkeys(TRANS, 1000),
            "encoders": [{"name": "encoder-" + alias, "codec": "vp9", "rate_controls": ["automatic", "cbr"],
                          "modes": [{"width": 640, "height": 360, "bitrate_kbps": 100, "fps": [15]},
                                    {"width": 720, "height": 480, "bitrate_kbps": 900, "fps": [30]}]}]}


def compile_for(phones=PHONES, selected=None, caps=None):
    return inventory.compile_inventory({"required_phones": list(phones), "transports": TRANS, "duration_s": 90},
        caps if caps is not None else {p: capability(p) for p in phones}, selected)


class BenchmarkInventoryTest(unittest.TestCase):
    def test_profile_extrema_use_offered_modes_transport_ceiling_and_nonempty_fps(self):
        caps = capability("s24")
        encoder = caps["encoders"][0]
        encoder["modes"] += [{"width": 1280, "height": 720, "fps": [24, 30], "bitrate_kbps": 1500},
                             {"width": 320, "height": 240, "fps": [], "bitrate_kbps": 50}]
        low = inventory.profile(caps, encoder, "automatic", "gatt", "low", "balanced")
        high = inventory.profile(caps, encoder, "cbr", "gatt", "high", "balanced")
        self.assertEqual((640, 360, 15, 100), tuple(low[key] for key in ("width", "height", "fps", "bitrate_kbps")))
        self.assertEqual((720, 480, 30, 900), tuple(high[key] for key in ("width", "height", "fps", "bitrate_kbps")))
        self.assertEqual("automatic", low["rate_control"])
        self.assertEqual("cbr", high["rate_control"])

    def test_add_unrelated_phone_and_provenance_change_preserve_existing_requests(self):
        old, _, _ = compile_for(PHONES[:3])
        new, _, _ = compile_for(PHONES[:4])
        for kind in old:
            self.assertTrue({c["id"] for c in old[kind]} <= {c["id"] for c in new[kind]})
        caps = {p: capability(p) for p in PHONES[:3]}
        caps["s24"]["fingerprint"] = "new-provenance-only"
        changed, _, _ = compile_for(PHONES[:3], caps=caps)
        self.assertEqual({c["id"] for v in old.values() for c in v},
                         {c["id"] for v in changed.values() for c in v})

    def test_request_change_creates_a_different_identity(self):
        before = {"source": "s24", "profile": {"bitrate_kbps": 500}, "duration_s": 90}
        after = copy.deepcopy(before)
        after["profile"]["bitrate_kbps"] = 501
        self.assertNotEqual(inventory.request_id(before), inventory.request_id(after))
        after = {**before, "duration_s": 91}
        self.assertNotEqual(inventory.request_id(before), inventory.request_id(after))

    def test_all_six_ordered_roles_transports_and_not_run_only(self):
        cases, catalog, summary = compile_for()
        pairs = set(itertools.product(itertools.permutations(PHONES, 2), TRANS))
        triples = set(itertools.product(itertools.permutations(PHONES, 3), TRANS))
        self.assertEqual(pairs, {((c["source"], c["receivers"][0]["phone"]), c["transport"])
                                 for c in cases["broadcast"]})
        self.assertEqual(pairs, {((c["caller"], c["callee"]), c["transport"]) for c in cases["call"]})
        self.assertEqual(triples, {((c["source"], *(n["phone"] for n in c["receivers"])), c["transport"])
                                  for c in cases["chain"]})
        self.assertIn("a55", summary["required_phones"])
        self.assertEqual({"NOT RUN"}, {c["state"] for v in cases.values() for c in v})
        self.assertEqual({"NOT RUN"}, {r["state"] for r in catalog["assignments"]})
        self.assertEqual(0, summary["passes"])
        self.assertEqual(0, summary["measured_executions"])

    def test_no_sender_encoder_blocks_but_receiver_needs_only_decoder(self):
        caps = {p: capability(p) for p in PHONES[:2]}
        caps["s22"]["encoders"] = []
        cases, catalog, _ = compile_for(PHONES[:2], caps=caps)
        self.assertEqual({"s24"}, {c["source"] for c in cases["broadcast"]})
        self.assertEqual([], cases["call"])
        blocked = [r for r in catalog["assignments"] if r["phones"][0] == "s22"]
        self.assertTrue(all(r["eligibility"] == "BLOCKED" and r["reason"] for r in blocked))

    def test_decoder_mismatch_missing_export_and_unselected_phone_stay_visible(self):
        caps = {p: capability(p) for p in PHONES[:3]}
        caps["s22"]["decoders"] = ["av1"]
        del caps["m31"]
        cases, catalog, _ = compile_for(PHONES[:3], selected=PHONES[:2], caps=caps)
        incompatible = next(r for r in catalog["assignments"] if r["kind"] == "broadcast"
                            and r["phones"] == ["s24", "s22"])
        self.assertEqual("BLOCKED", incompatible["eligibility"])
        self.assertTrue(incompatible["blocked_profiles"])
        self.assertTrue(all(r["eligibility"] == "BLOCKED" for r in catalog["assignments"] if "m31" in r["phones"]))
        _, narrowed, _ = compile_for(PHONES[:3], selected=PHONES[:2])
        self.assertTrue(all(r["eligibility"] == "UNMEASURED" for r in narrowed["assignments"] if "m31" in r["phones"]))

    def test_every_allocation_buffer_and_low_high_is_explicit_in_each_direction(self):
        cases, _, _ = compile_for(PHONES[:2])
        expected = set(itertools.product(("automatic", "cbr"), ("low", "balanced", "stable"), (100, 900)))
        for kind in ("broadcast", "call"):
            for source in PHONES[:2]:
                profiles = [c["profile"] for c in cases[kind] if c["source"] == source] if kind == "broadcast" else [
                    c["profiles"][source] for c in cases[kind]]
                self.assertEqual(expected, {(p["rate_control"], p["playback"], p["bitrate_kbps"]) for p in profiles})
                self.assertEqual({"encoder-" + source}, {p["codec"] for p in profiles})

    def test_symbolic_counts_grow_without_expanding_setting_products(self):
        _, catalog, _ = compile_for()
        chain = next(t for t in catalog["required_topology_space"] if t["kind"] == "homogeneous_chain"
                     and t["role_slots"] == 3)
        self.assertEqual(360, chain["assignment_count"])
        unsupported = [t for t in catalog["required_topology_space"] if t["kind"] == "mixed_chain"]
        self.assertTrue(all(t["eligibility"] == "BLOCKED" and t["reason"] for t in unsupported))
        self.assertLess(len(catalog["required_topology_space"]), 30)
        self.assertEqual(["low", "balanced", "stable"], catalog["full_setting_space"]["a55"]["buffers"])
        encoder = catalog["full_setting_space"]["a55"]["encoders"][0]
        self.assertEqual(2, encoder["geometry_count"])
        self.assertEqual([15, 30], encoder["fps_values"])
        self.assertEqual(24, encoder["profile_products_by_transport"]["classic"])

    def test_asymmetric_call_tiers_cover_both_directions_without_full_products(self):
        cases, _, _ = compile_for(PHONES[:2])
        for caller, callee, transport in itertools.product(PHONES[:2], PHONES[:2], TRANS):
            if caller == callee:
                continue
            tiers = {(c["profiles"][caller]["bitrate_kbps"], c["profiles"][callee]["bitrate_kbps"])
                for c in cases["call"] if c["caller"] == caller and c["callee"] == callee and c["transport"] == transport
                and not c.get("actions") and c["setup_action"] == "accept"}
            self.assertEqual({(100, 100), (900, 900), (100, 900), (900, 100)}, tiers)
        # Previously generated same-tier exact requests retain their identity.
        caps = {p: capability(p) for p in PHONES[:2]}
        request = {"caller": "s24", "callee": "s22", "transport": "classic", "profiles": {
            p: inventory.profile(caps[p], caps[p]["encoders"][0], "cbr" if p == "s24" else "automatic", "classic", "high", "balanced")
            for p in PHONES[:2]}, "setup_action": "accept", "duration_s": 90, "media_trace": True}
        self.assertTrue(inventory.request_id(request, "call") in {c["id"] for c in cases["call"]})

    def test_every_ordered_assignment_has_bounded_supported_lifecycle_templates(self):
        cases, catalog, _ = compile_for()
        requests = {c["id"]: c for values in cases.values() for c in values}
        expected = {"broadcast": {"broadcast_controls", "receiver_rejoin"},
            "chain": {"chain_controls", "chain_leaf_rejoin", "chain_middle_rejoin"},
            "call": {"call_controls", "call_decline", "call_cancel"}}
        for assignment in catalog["assignments"]:
            self.assertEqual(expected[assignment["kind"]], set(assignment["lifecycle_case_ids"]))
            for template, case_id in assignment["lifecycle_case_ids"].items():
                request = requests[case_id]
                self.assertEqual(120, request["duration_s"])
                if template == "call_decline" or template == "call_cancel":
                    self.assertEqual(template.removeprefix("call_"), request["setup_action"])
                    self.assertFalse(request.get("actions"))
                    continue
                self.assertEqual("end", request["actions"][-1]["action"])
                self.assertTrue(all(0 <= a["at_s"] < request["duration_s"] for a in request["actions"]))
                if "rejoin" in template:
                    kill, restart = request["actions"][:2]
                    index = 1 if template == "chain_middle_rejoin" else -1
                    self.assertEqual(assignment["phones"][index], kill["phone"])
                    self.assertEqual(("kill", "restart_receiver"), (kill["action"], restart["action"]))
                    self.assertEqual(kill["phone"], restart["phone"])
                if template == "call_controls":
                    for phone in assignment["phones"]:
                        self.assertEqual({"mute", "unmute", "video_off", "video_on", "flip", "background", "foreground"},
                            {a["action"] for a in request["actions"] if a["phone"] == phone} - {"end"})

    def test_generated_requests_validate_against_the_exact_retained_capabilities(self):
        from run_benchmark_suite import validate_capabilities
        caps = {p: capability(p) for p in PHONES[:3]}
        caps["s22"]["encoders"] = []
        cases, _, _ = compile_for(PHONES[:3], caps=caps)
        for values in cases.values():
            for request in values:
                validate_capabilities(request, caps)
        self.assertTrue(any(c["source"] == "s24" and "s22" in {n["phone"] for n in c["receivers"]}
                            for c in cases["chain"]))

    def test_call_endpoint_buffers_are_independent_when_broadcast_buffers_do_not_match(self):
        from run_benchmark_suite import validate_capabilities
        caps = {p: capability(p) for p in PHONES[:2]}
        caps["s24"]["playback"] = ["low"]
        caps["s22"]["playback"] = ["stable"]
        cases, _, _ = compile_for(PHONES[:2], caps=caps)
        self.assertFalse(cases["broadcast"])
        self.assertTrue(cases["call"])
        for request in cases["call"]:
            self.assertEqual("low", request["profiles"]["s24"]["playback"])
            self.assertEqual("stable", request["profiles"]["s22"]["playback"])
            validate_capabilities(request, caps)

    def test_unsupported_exported_call_fps_stays_blocked_without_substitution(self):
        caps = {p: capability(p) for p in PHONES[:2]}
        caps["s24"]["encoders"][0]["modes"][1]["fps"] = [120]
        cases, catalog, _ = compile_for(PHONES[:2], caps=caps)
        for request in cases["call"]:
            self.assertLessEqual(request["profiles"]["s24"]["fps"], 60)
        blocked = [p for r in catalog["assignments"] if r["kind"] == "call" for p in r["blocked_profiles"]
                   if "request" in p and p["request"]["profiles"]["s24"]["fps"] == 120]
        self.assertTrue(blocked)
        self.assertTrue(all(p["eligibility"] == "BLOCKED" and p["state"] == "NOT RUN" and p["reason"] for p in blocked))

    def test_input_dimensions_are_unique_native_requests_and_do_not_claim_import(self):
        cases, _, _ = compile_for(PHONES[:3])
        contract = inventory.input_requirements(cases)
        expected = {(640, 360, 15), (720, 480, 30)}
        self.assertEqual(expected, {(d["width"], d["height"], d["fps"]) for d in contract["dimensions"]})
        self.assertEqual(2, len(contract["dimensions"]))
        self.assertEqual("I420", contract["raw_contract"]["format"])
        self.assertEqual(8, contract["raw_contract"]["bit_depth"])
        self.assertEqual("UNMEASURED", contract["integration"]["status"])
        for dimension in contract["dimensions"]:
            self.assertEqual(dimension["width"] * dimension["height"] * 3 // 2, dimension["frame_bytes"])
            self.assertEqual({"numerator": 1, "denominator": dimension["fps"]}, dimension["timebase"])
        self.assertFalse(any("raw_fixture_file" in c or "raw_fixture_files" in c for values in cases.values() for c in values))

    def test_future_phone_extends_role_space_without_renaming_old_requests(self):
        old, _, _ = compile_for(PHONES[:3])
        future = "future_phone"
        caps = {p: capability(p) for p in PHONES[:3]}
        caps[future] = copy.deepcopy(caps["s24"])
        caps[future]["node_prefix"] = "000000000007"
        caps[future]["encoders"][0]["name"] = "future_encoder"
        new, catalog, _ = compile_for(PHONES[:3] + [future], caps=caps)
        self.assertTrue({c["id"] for v in old.values() for c in v} <= {c["id"] for v in new.values() for c in v})
        self.assertTrue(any(c["source"] == future for c in new["chain"]))
        self.assertTrue(any(c["receivers"][0]["phone"] == future for c in new["chain"]))
        self.assertTrue(any(c["receivers"][-1]["phone"] == future for c in new["chain"]))
        self.assertEqual(72, next(t["assignment_count"] for t in catalog["required_topology_space"]
                                 if t["kind"] == "homogeneous_chain" and t["role_slots"] == 3))

    def test_a_phone_that_moves_from_required_to_extension_keeps_every_request_id(self):
        # The owner's fixed phones changed (Xiaomi and A55 now extend the inventory): nothing they take part in is dropped.
        wide = PHONES + ["a15s"]
        caps = {p: capability(p) if p in PHONES else {**capability("a55"), "node_prefix": f"{7:012x}"} for p in wide}
        scope = {"required_phones": PHONES[:4] + ["a15s"], "extension_phones": PHONES[4:], "transports": TRANS,
                 "duration_s": 90}
        old, _, _ = inventory.compile_inventory({**scope, "required_phones": PHONES, "extension_phones": []},
                                                {p: caps[p] for p in PHONES})
        new, inventory_doc, summary = inventory.compile_inventory(scope, caps)
        for kind in old:
            self.assertTrue({c["id"] for c in old[kind]} <= {c["id"] for c in new[kind]}, kind)
        self.assertEqual(PHONES[:4] + ["a15s"], inventory_doc["required_phones"])
        self.assertEqual(PHONES[4:], inventory_doc["extension_phones"])
        self.assertEqual(PHONES[4:], summary["extension_phones"])
        self.assertEqual(set(wide), set(inventory_doc["selected_phones"]))  # compiled: offline selection is not availability
        self.assertTrue(any("a15s" in c["caller"] + c["callee"] for c in new["call"]))

    def test_without_extension_phones_the_inventory_is_what_it_was(self):
        _, doc, summary = compile_for(PHONES[:3])
        self.assertNotIn("extension_phones", doc)
        self.assertNotIn("extension_phones", summary)

    def test_an_extension_phone_cannot_also_be_required(self):
        with self.assertRaises(ValueError):
            inventory.compile_inventory({"required_phones": PHONES[:3], "extension_phones": PHONES[2:4],
                                         "transports": TRANS, "duration_s": 90}, {p: capability(p) for p in PHONES[:4]})

    def test_carrier_eligibility_and_sweep_pairs_may_name_extension_phones(self):
        scope = {"required_phones": PHONES[:3], "extension_phones": PHONES[3:4], "transports": TRANS, "duration_s": 90,
                 "carriers": ["wifi_direct"], "carrier_eligibility": {"wifi_direct": PHONES[:4]},
                 "settings_sweep": {"duration_s": 45, "pairs": [[PHONES[2], PHONES[3]]]}}
        inventory.compile_inventory(scope, {p: capability(p) for p in PHONES[:4]})
        scope["carrier_eligibility"] = {"wifi_direct": PHONES[:5]}
        with self.assertRaises(ValueError):
            inventory.compile_inventory(scope, {p: capability(p) for p in PHONES[:4]})

    def test_retiring_an_exact_requirement_removes_only_its_request(self):
        old, _, _ = compile_for(PHONES[:2])
        retired_id = old["broadcast"][0]["id"]
        caps = {p: capability(p) for p in PHONES[:2]}
        scope = {"required_phones": PHONES[:2], "transports": TRANS, "duration_s": 90,
                 "retired_requirements": [{"id": retired_id, "reason": "Requirement explicitly removed"}]}
        new, catalog, _ = inventory.compile_inventory(scope, caps)
        self.assertEqual({c["id"] for v in old.values() for c in v} - {retired_id},
                         {c["id"] for v in new.values() for c in v})
        self.assertIn(retired_id, {i for r in catalog["assignments"] for i in r["retired_case_ids"]})

    def test_retirement_file_retires_many_vanished_requests_with_one_reason(self):
        caps = {p: capability(p) for p in PHONES[:2]}
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "retired.json"
            path.write_text(json.dumps({"reason": "Capability refresh changed the sampled profiles",
                                        "ids": ["gone-one", "gone-two"]}))
            scope = {"required_phones": PHONES[:2], "transports": TRANS, "duration_s": 90,
                     "retired_requirements": [{"id": "inline", "reason": "Removed by the owner"}],
                     "retired_requirement_files": [str(path)]}
            _, catalog, _ = inventory.compile_inventory(scope, caps)
            self.assertEqual([("inline", "Removed by the owner"),
                              ("gone-one", "Capability refresh changed the sampled profiles"),
                              ("gone-two", "Capability refresh changed the sampled profiles")],
                             [(row["id"], row["reason"]) for row in catalog["retired_requirements"]])
            for bad in ({"reason": " ", "ids": ["x"]}, {"reason": "ok", "ids": [""]}, {"reason": "ok"}):
                path.write_text(json.dumps(bad))
                with self.assertRaisesRegex(ValueError, "explicit reason"):
                    inventory.compile_inventory(scope, caps)
            path.write_text(json.dumps({"reason": "ok", "ids": ["inline"]}))
            with self.assertRaisesRegex(ValueError, "unique"):
                inventory.compile_inventory(scope, caps)

    def test_retirement_records_require_reason_and_do_not_rewrite_requests(self):
        scope = {"required_phones": PHONES[:2], "retired_requirements": [{"id": "old-request", "reason": "User removed obsolete setting"}]}
        caps = {p: capability(p) for p in PHONES[:2]}
        cases, catalog, _ = inventory.compile_inventory(scope, caps)
        self.assertEqual(scope["retired_requirements"], catalog["retired_requirements"])
        self.assertTrue(all(c["state"] == "NOT RUN" for values in cases.values() for c in values))
        scope["retired_requirements"][0]["reason"] = ""
        with self.assertRaisesRegex(ValueError, "explicit reason"):
            inventory.compile_inventory(scope, caps)

    def test_cli_has_no_device_io_and_never_overwrites_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary).resolve()
            scope = {"required_phones": PHONES[:2], "registry": str(folder / "registry.json"), "capabilities": {}}
            for p in PHONES[:2]:
                path = folder / (p + ".json")
                path.write_text(json.dumps(capability(p)), encoding="utf-8")
                scope["capabilities"][p] = str(path)
            (folder / "registry.json").write_text(json.dumps({"devices": {
                p: {"node_prefix": capability(p)["node_prefix"]} for p in PHONES[:2]}}), encoding="utf-8")
            (folder / "scope.json").write_text(json.dumps(scope), encoding="utf-8")
            args = ["--scope", str(folder / "scope.json"), "--output", str(folder / "new")]
            with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, inventory.main(args))
                with self.assertRaises(FileExistsError):
                    inventory.main(args)
            self.assertEqual(0, json.loads((folder / "new/summary.json").read_text())["passes"])
            self.assertEqual(2, len(json.loads((folder / "new/benchmark-bundle.json").read_text())["suites"]))
            self.assertTrue((folder / "new/input_requirements.json").is_file())
            from run_benchmark_suite import load_jobs
            registry = {p: {"serial": "offline-" + p, "node_prefix": capability(p)["node_prefix"]} for p in PHONES[:2]}
            with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")):
                jobs = load_jobs(folder / "new/benchmark-bundle.json", registry)
                self.assertTrue(jobs)
                relative_args = ["--scope", "scope.json", "--output", "benchmarks/catalog/reviewed"]
                with patch.object(inventory, "ROOT", folder), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, inventory.main(relative_args))
                self.assertEqual({j["case"]["id"] for j in jobs},
                    {j["case"]["id"] for j in load_jobs(folder / "benchmarks/catalog/reviewed/benchmark-bundle.json", registry)})

    def test_short_settings_sweep_covers_interior_values_and_valid_boundary_interactions(self):
        caps = capability("s24")
        encoder = caps["encoders"][0]
        encoder["modes"] = [
            {"width": 640, "height": 360, "fps": [15, 24, 30], "bitrate_kbps": rate}
            for rate in (100, 500, 900)] + [
            {"width": 720, "height": 480, "fps": [24, 30], "bitrate_kbps": rate}
            for rate in (100, 500, 900)] + [
            {"width": 1280, "height": 720, "fps": [30], "bitrate_kbps": 1500}]
        profiles, proof = inventory.settings_profiles(caps, encoder, "gatt", caps["playback"])
        self.assertEqual({(640, 360), (720, 480)}, {(p["width"], p["height"]) for p in profiles})
        self.assertEqual({15, 24, 30}, {p["fps"] for p in profiles})
        self.assertEqual({100, 500, 900}, {p["bitrate_kbps"] for p in profiles})
        self.assertEqual({"automatic", "cbr"}, {p["rate_control"] for p in profiles})
        self.assertEqual(set(caps["playback"]), {p["playback"] for p in profiles})
        for candidate in proof["boundaries"].values():
            self.assertIn(candidate, [[p[k] for k in ("width", "height", "fps", "bitrate_kbps")] for p in profiles])
        self.assertEqual([640, 360, 15, 900], proof["boundaries"]["low_fps_high_rate"])
        self.assertEqual([720, 480, 30, 100], proof["boundaries"]["large_geometry_low_rate"])
        self.assertEqual([720, 480, 30, 900], proof["boundaries"]["maximum_pixel_rate"])
        offered = {(m["width"], m["height"], f, m["bitrate_kbps"]) for m in encoder["modes"] for f in m["fps"]}
        self.assertTrue({tuple(p[k] for k in ("width", "height", "fps", "bitrate_kbps")) for p in profiles} <= offered)
        self.assertEqual(15 * 2 * 3, proof["profile_product_count"])
        self.assertEqual(proof["profile_product_count"] - len(profiles), proof["omitted_profile_products"])
        self.assertTrue(proof["single_value_coverage_complete"])
        self.assertEqual("UNMEASURED", proof["remaining_product_state"])

    def test_settings_sweep_is_deterministic_linear_and_handles_sparse_capabilities(self):
        caps = capability("s24")
        encoder = caps["encoders"][0]
        encoder["modes"] = [{"width": 640, "height": 360, "fps": [15, 24, 30], "bitrate_kbps": r} for r in range(100, 1000, 100)]
        profiles, proof = inventory.settings_profiles(caps, encoder, "classic", caps["playback"])
        self.assertLessEqual(len(profiles), 9 + 3 + 2 + 3)
        changed = copy.deepcopy(encoder)
        changed["modes"].reverse()
        changed["rate_controls"].reverse()
        for row in changed["modes"]:
            row["fps"].reverse()
        self.assertEqual((profiles, proof), inventory.settings_profiles(caps, changed, "classic", list(reversed(caps["playback"]))))
        changed["modes"] = [{"width": 640, "height": 360, "fps": [15], "bitrate_kbps": 900},
                            {"width": 720, "height": 480, "fps": [30], "bitrate_kbps": 100}]
        sparse, proof = inventory.settings_profiles(caps, changed, "classic", caps["playback"])
        self.assertTrue(proof["single_value_coverage_complete"])
        self.assertTrue(all((p["width"], p["fps"], p["bitrate_kbps"]) in {(640, 15, 900), (720, 30, 100)} for p in sparse))
        for field, value in (("modes", []), ("rate_controls", [])):
            empty = {**changed, field: value}
            selected, proof = inventory.settings_profiles(caps, empty, "classic", caps["playback"])
            self.assertEqual([], selected)
            self.assertFalse(proof["single_value_coverage_complete"])
        self.assertEqual([], inventory.settings_profiles(caps, changed, "classic", [])[0])

    def test_optional_settings_sweep_preserves_old_requests_and_adds_only_configured_pairs(self):
        phones = PHONES[:3]
        caps = {p: capability(p) for p in phones}
        scope = {"required_phones": phones, "transports": TRANS, "duration_s": 90}
        old, _, _ = inventory.compile_inventory(scope, caps)
        scope["settings_sweep"] = {"duration_s": 45, "pairs": [["s24", "s22"], ["s22", "m31"], ["m31", "s24"]]}
        new, catalog, summary = inventory.compile_inventory(scope, caps)
        all_new = {c["id"]: c for v in new.values() for c in v}
        self.assertTrue(all(all_new[c["id"]] == c for v in old.values() for c in v))
        self.assertEqual(old["call"], new["call"])
        self.assertEqual(old["chain"], new["chain"])
        diagnostic = [c for c in new["broadcast"] if c["duration_s"] == 45]
        self.assertTrue(diagnostic)
        self.assertEqual({tuple(p) for p in scope["settings_sweep"]["pairs"]}, {(c["source"], c["receivers"][0]["phone"]) for c in diagnostic})
        self.assertEqual({"NOT RUN"}, {c["state"] for c in diagnostic})
        self.assertEqual(sorted(c["id"] for c in diagnostic), catalog["settings_sweep"]["case_ids"])
        self.assertEqual(len(diagnostic), summary["settings_sweep_cases"])
        scope["required_phones"] = PHONES[:4]
        caps["asus"] = capability("asus")
        expanded, _, _ = inventory.compile_inventory(scope, caps)
        self.assertEqual({c["id"] for c in diagnostic}, {c["id"] for c in expanded["broadcast"] if c["duration_s"] == 45})
        from run_benchmark_suite import validate_capabilities
        for case in diagnostic:
            validate_capabilities(case, caps)

    def test_settings_sweep_blocked_and_unselected_scopes_stay_explicit(self):
        scope = {"required_phones": PHONES[:2], "settings_sweep": {"duration_s": 45, "pairs": [["s24", "s22"]]}}
        caps = {p: capability(p) for p in PHONES[:2]}
        caps["s22"]["decoders"] = []
        _, catalog, _ = inventory.compile_inventory(scope, caps)
        proof = catalog["settings_sweep"]
        self.assertEqual([], proof["case_ids"])
        self.assertTrue(all(r["eligibility"] == "BLOCKED" and r["reason"] for r in proof["coverage"]))
        _, catalog, _ = inventory.compile_inventory(scope, {p: capability(p) for p in PHONES[:2]}, selected=[])
        self.assertEqual([], catalog["settings_sweep"]["case_ids"])
        self.assertTrue(all(r["eligibility"] == "UNMEASURED" for r in catalog["settings_sweep"]["coverage"]))
        caps["s24"]["encoders"] = []
        _, catalog, _ = inventory.compile_inventory(scope, caps)
        self.assertEqual(3, len(catalog["settings_sweep"]["coverage"]))
        self.assertTrue(all(r["eligibility"] == "BLOCKED" and r["reason"] for r in catalog["settings_sweep"]["coverage"]))
        for settings in ({"duration_s": 0, "pairs": [["s24", "s22"]]},
                         {"duration_s": float("nan"), "pairs": [["s24", "s22"]]},
                         {"duration_s": 90, "pairs": [["s24", "s22"]]},
                         {"pairs": [["s24", "s24"]]}, {"pairs": [["s24", "other"]]},
                         {"pairs": [["s24", "s22"], ["s24", "s22"]]}):
            with self.assertRaisesRegex(ValueError, "Settings sweep"):
                inventory.compile_inventory({**scope, "settings_sweep": settings}, caps)

    def test_cli_settings_bundle_has_diagnostic_gates_and_empty_selection_never_expands(self):
        from run_benchmark_suite import FAMILIES, load_jobs
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            scope = {"required_phones": PHONES[:2], "registry": str(folder / "registry.json"), "capabilities": {},
                     "settings_sweep": {"duration_s": 45, "pairs": [["s24", "s22"]]}}
            registry = {p: {"serial": "offline-" + p, "node_prefix": capability(p)["node_prefix"]} for p in PHONES[:2]}
            for p in PHONES[:2]:
                path = folder / (p + ".json")
                path.write_text(json.dumps(capability(p)), encoding="utf-8")
                scope["capabilities"][p] = str(path)
            (folder / "registry.json").write_text(json.dumps({"devices": registry}), encoding="utf-8")
            (folder / "scope.json").write_text(json.dumps(scope), encoding="utf-8")
            with patch("subprocess.run", side_effect=AssertionError("Device I/O forbidden")), contextlib.redirect_stdout(io.StringIO()):
                inventory.main(["--scope", str(folder / "scope.json"), "--output", str(folder / "selected")])
                full = load_jobs(folder / "selected/benchmark-bundle.json", registry)
                short = load_jobs(folder / "selected/settings-sweep-bundle.json", registry)
                self.assertTrue(short)
                self.assertEqual({j["case"]["id"] for j in short}, {j["case"]["id"] for j in full if j["case"]["duration_s"] == 45})
                self.assertTrue(all(j["required_families"] == list(inventory.SWEEP_FAMILIES) for j in short))
                self.assertTrue(all(j["required_families"] == list(FAMILIES) for j in full if j["case"]["duration_s"] != 45))
                inventory.main(["--scope", str(folder / "scope.json"), "--phones=", "--output", str(folder / "paused")])
            self.assertEqual([], json.loads((folder / "paused/settings-sweep-bundle.json").read_text())["suites"])
            self.assertEqual(0, json.loads((folder / "paused/summary.json").read_text())["settings_sweep_cases"])


    def test_carrier_dimension_adds_new_ids_and_never_changes_existing_ones(self):
        base, base_catalog, _ = compile_for(PHONES[:3])
        scope = {"required_phones": PHONES[:3], "transports": TRANS, "duration_s": 90,
                 "carriers": ["wifi_aware", "wifi_direct", "wifi_aware_native"],
                 "carrier_eligibility": {"wifi_aware": ["s24", "s22"], "wifi_direct": PHONES[:3],
                                         "wifi_aware_native": ["s24", "s22"]}}
        cases, catalog, _ = inventory.compile_inventory(scope, {p: capability(p) for p in PHONES[:3]})
        for kind in base:
            self.assertEqual({c["id"] for c in base[kind]}, {c["id"] for c in cases[kind] if "carrier" not in c})
            self.assertEqual([c for c in base[kind]], [c for c in cases[kind] if "carrier" not in c])
        added = [c for kind in cases.values() for c in kind if "carrier" in c]
        self.assertTrue(added)
        self.assertFalse({c["id"] for c in added} & {c["id"] for v in base.values() for c in v})
        self.assertEqual({"wifi_aware", "wifi_direct"}, {c["carrier"] for c in added})  # native is blocked, never a case
        self.assertTrue(all(set(c["phones"] if "phones" in c else []) <= set(PHONES[:3]) for c in added))
        wifi_aware = [c for c in added if c["carrier"] == "wifi_aware"]
        self.assertTrue(all(c.get("source", c.get("caller")) in {"s24", "s22"} for c in wifi_aware))
        native = [r for r in catalog["assignments"] if r.get("carrier") == "wifi_aware_native" and r["case_ids"] == []]
        self.assertTrue(native and all(r["eligibility"] == "BLOCKED" for r in native))
        self.assertTrue(any("m31" in r["phones"] and r.get("carrier") == "wifi_aware" and r["eligibility"] == "BLOCKED"
                            for r in catalog["assignments"]))
        self.assertEqual(0, len([r for r in base_catalog["assignments"] if "carrier" in r]))

    def test_same_scope_without_carriers_is_byte_identical(self):
        a = compile_for(PHONES[:3])
        scope = {"required_phones": PHONES[:3], "transports": TRANS, "duration_s": 90, "carriers": []}
        b = inventory.compile_inventory(scope, {p: capability(p) for p in PHONES[:3]})
        self.assertEqual(json.dumps(a, sort_keys=True), json.dumps(b, sort_keys=True))

    def test_unknown_carrier_and_unknown_eligible_phone_are_refused(self):
        caps = {p: capability(p) for p in PHONES[:2]}
        with self.assertRaises(ValueError):
            inventory.compile_inventory({"required_phones": PHONES[:2], "transports": TRANS, "carriers": ["lte"]}, caps)
        with self.assertRaises(ValueError):
            inventory.compile_inventory({"required_phones": PHONES[:2], "transports": TRANS, "carriers": ["wifi_aware"],
                                         "carrier_eligibility": {"wifi_aware": ["nobody"]}}, caps)


if __name__ == "__main__":
    unittest.main()
