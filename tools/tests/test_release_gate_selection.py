"""The release-gate selection: tagged user-launch calls on phone-disjoint lanes inside the two-hour budget.

The tests read the generator's output the way a lane owner would: which phones a lane needs, whether every phone and
both call directions are covered, what the planner would run first, and whether the same inputs give the same ids.
"""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
import release_gate_selection as gate
import run_benchmark_suite as runner

ROOT = Path(__file__).resolve().parents[2]
SCOPE = json.loads((ROOT / "tools/benchmark_scope.json").read_text(encoding="utf-8-sig"))
REGISTRY = json.loads((ROOT / "tools/benchmark_devices.example.json").read_text(encoding="utf-8-sig"))["devices"]
CAPABILITIES, PROVENANCE = {}, {}
for phone in gate.RELEASE_PHONES:
    raw = (ROOT / SCOPE["capabilities"][phone]).read_bytes()
    CAPABILITIES[phone] = json.loads(raw.decode("utf-8-sig"))
    PROVENANCE[phone] = {"path": SCOPE["capabilities"][phone], "sha256": hashlib.sha256(raw).hexdigest()}


def build(capabilities=None, **options):
    options = {"date": "2026-11-10", "seed": 20261110, **options}
    return gate.build(SCOPE, capabilities or CAPABILITIES, PROVENANCE, REGISTRY, **options)


def lane_ids(bundle):
    return bundle["suites"][0]["case_ids"]


class Selection(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suite, cls.bundles, cls.selection = build()
        cls.cases = {case["id"]: case for case in cls.suite["cases"]}

    def lane_cases(self, lane):
        return [self.cases[i] for i in lane_ids(self.bundles[lane])]

    def test_user_launch_calls_about_the_target_every_one_tagged_and_idle(self):
        self.assertTrue(0.9 * gate.TARGET_CALLS <= len(self.cases) <= 1.1 * gate.TARGET_CALLS)
        self.assertEqual(self.selection["calls"], len(self.cases))
        for case in self.cases.values():
            self.assertIs(case["tunables"]["user_launch"], True)
            self.assertEqual(case["release_gate"]["selection"], self.selection["id"])
            self.assertEqual(case["state"], "NOT RUN")
            self.assertIn("moving", case["environment"])
            if not case.get("relay"):
                self.assertEqual(case["callee_idle_s"], 12)
        counted = [c for c in self.cases.values() if "callee_idle_s" in c]
        self.assertEqual(self.selection["counted_by_join_gate"], len(counted))
        self.assertGreaterEqual(len(counted), 20)  # release_join.MINIMUM_CALLS

    def test_relayed_calls_are_on_demand_with_the_persons_setting_and_no_idle_wait(self):
        relayed = [c for c in self.cases.values() if c.get("relay")]
        self.assertTrue(relayed)
        for case in relayed:
            self.assertEqual((case["relay_shape"], case["user_settings"]), ("on_demand", {"relayed_calls": True}))
            self.assertNotIn("callee_idle_s", case)  # the runner refuses it on a relayed route
            self.assertIn(case["transport"], ("ble", "gatt"))
        for case in relayed:
            lane = self.selection["lanes"][case["release_gate"]["lane"]]
            self.assertEqual(case["relay"], lane["phones"][1])  # the chain's middle phone relays
            self.assertEqual({case["caller"], case["callee"]}, {lane["phones"][0], lane["phones"][2]})

    def test_every_required_phone_is_in_some_window_and_both_directions_are_covered(self):
        placed = {p for window in self.selection["windows"] for lane in window["lanes"]
                  for p in self.bundles[lane]["phones"]}
        self.assertEqual(placed, set(gate.RELEASE_PHONES))  # nobody left out, nobody extra (no Realme)
        for window in self.selection["windows"]:
            for lane in window["lanes"]:
                directions = {}
                for case in self.lane_cases(lane):
                    key = (case["caller"], case["callee"])
                    directions[key] = directions.get(key, 0) + 1
                for (caller, callee), count in directions.items():
                    self.assertEqual(directions.get((callee, caller)), count, f"{lane}: unequal directions")
        called = {c["caller"] for c in self.cases.values()} | {c["callee"] for c in self.cases.values()}
        self.assertEqual(called, set(gate.RELEASE_PHONES))

    def test_a_phone_may_sit_out_a_window_but_lanes_of_a_window_never_share_one(self):
        sitting_out = [(w["window"], p) for w in self.selection["windows"] for p in gate.RELEASE_PHONES
                       if p not in {q for lane in w["lanes"] for q in self.bundles[lane]["phones"]}]
        self.assertTrue(sitting_out)  # five phones: somebody rests in each window
        original = gate.LAYOUT
        try:
            gate.LAYOUT = original[:1]  # direct window only: the M31 is nowhere
            with self.assertRaisesRegex(ValueError, "at least one window"):
                build()
            gate.LAYOUT = (*original[:1], {"window": "relay", "lanes": (
                {"lane": "relay1", "chain": ("s22", "asus", "a15s")}, {"lane": "lane3", "phones": ("s24", "asus")})})
            with self.assertRaisesRegex(ValueError, "share a phone"):
                build()
            gate.LAYOUT = (*original, {"window": "extra", "lanes": ({"lane": "lane9", "phones": ("s24", "realme")},)})
            with self.assertRaisesRegex(ValueError, "at least one window"):  # a phone outside the release set
                build()
        finally:
            gate.LAYOUT = original

    def test_a_window_may_mix_a_relay_chain_with_a_direct_pair_that_is_counted(self):
        for window in self.selection["windows"]:
            kinds = {"chain" if len(self.bundles[lane]["phones"]) == 3 else "pair" for lane in window["lanes"]}
            if kinds == {"chain", "pair"}:
                pair = [l for l in window["lanes"] if len(self.bundles[l]["phones"]) == 2][0]
                self.assertTrue(all("callee_idle_s" in c and not c.get("relay") for c in self.lane_cases(pair)))
                return
        self.fail("no window mixes a chain with a pair")

    def test_a_lane_can_override_its_quota_and_the_others_keep_theirs(self):
        original = gate.LAYOUT
        try:
            gate.LAYOUT = tuple({**w, "lanes": tuple({**l, "quotas": {**l.get("quotas", {}), "direct_lane_calls": 28}}
                                                     if l["lane"] == "lane2" else l for l in w["lanes"])}
                                for w in original)
            _, _, selection = build()
        finally:
            gate.LAYOUT = original
        self.assertEqual(selection["lanes"]["lane2"]["calls"], 28)
        self.assertEqual(selection["lanes"]["lane1"]["calls"], self.selection["lanes"]["lane1"]["calls"])

    def test_lanes_of_a_window_share_no_phone_and_every_call_stays_in_its_lane(self):
        for window in self.selection["windows"]:
            seen = set()
            for lane in window["lanes"]:
                phones = set(self.bundles[lane]["phones"])
                self.assertFalse(seen & phones, f"{lane} shares a phone")
                seen |= phones
                for case in self.lane_cases(lane):
                    route = {case["caller"], case["callee"], *([case["relay"]] if case.get("relay") else [])}
                    self.assertLessEqual(route, phones)
        ids = [i for bundle in self.bundles.values() for i in lane_ids(bundle)]
        self.assertEqual(sorted(ids), sorted(self.cases))  # every case belongs to exactly one lane

    def test_carriers_follow_the_phones_that_can_do_them(self):
        aware = [c for c in self.cases.values() if c.get("carrier") == "wifi_aware"]
        self.assertTrue(aware)
        for case in aware:
            self.assertEqual({case["caller"], case["callee"]}, {"s22", "s24"})
        direct = [c for c in self.cases.values() if c.get("carrier") == "wifi_direct"]
        listed = set(SCOPE["carrier_eligibility"]["wifi_direct"])
        self.assertEqual({p for c in direct for p in (c["caller"], c["callee"])}, set(gate.RELEASE_PHONES) & listed)
        for case in aware + direct:
            self.assertEqual(case["user_settings"], {"wifi": True})
            self.assertEqual(case["callee_idle_s"], 12)
        self.assertEqual({c["carrier"] for c in self.cases.values() if "carrier" in c}, {"wifi_aware", "wifi_direct"})

    def test_every_endpoint_takes_the_apps_own_video_choice_and_requests_no_profile(self):
        for case in self.cases.values():
            self.assertEqual({case["caller"]: {"profile_source": "app_default"},
                              case["callee"]: {"profile_source": "app_default"}}, case["profiles"])
            self.assertEqual(set(), case.get("user_settings", {}).keys() & {"video_fps", "video_width", "video_bitrate_kbps"})
            gate.validate_call_case(case, REGISTRY)  # the runner accepts every one as written
        self.assertEqual("app_default", self.selection["profile_source"])
        self.assertEqual({"video_width": 426, "video_height": 240, "video_fps": 15, "video_bitrate_kbps": 200},
                         self.selection["app_default_video"])

    def test_a_changed_shipped_video_default_is_a_new_selection(self):
        original = dict(gate.APP_DEFAULT_VIDEO)
        try:
            gate.APP_DEFAULT_VIDEO["video_fps"] = 30
            _, _, changed = build()
        finally:
            gate.APP_DEFAULT_VIDEO.clear()
            gate.APP_DEFAULT_VIDEO.update(original)
        self.assertNotEqual(changed["id"], self.selection["id"])

    def test_wifi_eligibility_is_read_from_the_scope(self):
        scope = copy.deepcopy(SCOPE)
        scope["carrier_eligibility"]["wifi_direct"] = [p for p in scope["carrier_eligibility"]["wifi_direct"] if p != "a15s"]
        scope["carrier_eligibility"]["wifi_aware"] = ["s24"]  # the Aware pair needs both phones listed
        suite, bundles, selection = gate.build(scope, CAPABILITIES, PROVENANCE, REGISTRY, date="2026-11-10", seed=20261110)
        by_id = {c["id"]: c for c in suite["cases"]}
        lane2 = [by_id[i] for i in lane_ids(bundles["lane2"])]
        self.assertFalse([c for c in lane2 if c.get("carrier")])  # a15s is not listed: no Wi-Fi call on its pair
        lane3 = [by_id[i] for i in lane_ids(bundles["lane3"])]
        self.assertTrue([c for c in lane3 if c.get("carrier") == "wifi_direct"])  # s24 and m31 still are
        self.assertNotEqual(selection["id"], self.selection["id"])
        lane1 = [by_id[i] for i in lane_ids(bundles["lane1"])]
        self.assertTrue([c for c in lane1 if c.get("carrier") == "wifi_direct"])
        self.assertFalse([c for c in lane1 if c.get("carrier") == "wifi_aware"])  # s22 is no longer listed for Aware

    def test_a_stale_scope_is_refused(self):
        scope = copy.deepcopy(SCOPE)
        scope["required_phones"] = ["s24", "s22", "m31", "asus", "a15s", "realme"]
        with self.assertRaisesRegex(ValueError, "required_phones differ"):
            gate.build(scope, CAPABILITIES, PROVENANCE, REGISTRY, date="2026-11-10", seed=20261110)

    def test_a_pair_whose_encoders_nobody_decodes_is_refused(self):
        capabilities = copy.deepcopy(CAPABILITIES)
        capabilities["a15s"]["decoders"] = []
        with self.assertRaisesRegex(ValueError, "is decoded by a15s"):
            build(capabilities)

    def test_every_transport_a_pair_offers_is_requested_and_one_it_lacks_is_not(self):
        for lane in ("lane1", "lane2", "lane3"):  # each direct pair tries all three, so every phone sees classic
            plain = {c["transport"] for c in self.lane_cases(lane) if "carrier" not in c}
            self.assertEqual(plain, {"classic", "ble", "gatt"})
        capabilities = copy.deepcopy(CAPABILITIES)
        capabilities["a15s"]["transport_max_kbps"]["classic"] = 100  # below the app's default 200 kbps video rate
        suite, bundles, _ = build(capabilities)
        by_id = {c["id"]: c for c in suite["cases"]}
        lane2 = [by_id[i] for i in lane_ids(bundles["lane2"])]
        self.assertNotIn("classic", {c["transport"] for c in lane2})
        self.assertEqual(len(lane2), self.selection["lanes"]["lane2"]["calls"])  # the call count holds

    def test_estimate_fits_the_two_hour_gate_and_each_lane_has_a_figure(self):
        self.assertLessEqual(self.selection["estimated_total_s"], gate.BUDGET_S)
        self.assertLess(self.selection["estimated_total_s"], 2 * 3600)
        for window in self.selection["windows"]:
            slowest = max(self.selection["lanes"][lane]["estimated_s"] for lane in window["lanes"])
            self.assertEqual(window["longest_lane_s"], slowest)
        by_hand = sum(self.cases[i]["duration_s"] + gate.TIMING_BASIS[gate.kind_of(self.cases[i])]["overhead_s"]
                      for i in lane_ids(self.bundles["lane1"]))
        self.assertEqual(by_hand, self.selection["lanes"]["lane1"]["estimated_s"])
        total = sum(w["setup_s"] + w["longest_lane_s"] for w in self.selection["windows"])
        self.assertEqual(total, self.selection["estimated_total_s"])

    def test_a_selection_that_cannot_finish_in_the_budget_is_refused(self):
        with self.assertRaises(ValueError):
            build(budget_s=3000)
        with self.assertRaises(ValueError):
            build(duration_s=240)

    def test_same_inputs_give_the_same_ids_and_order_and_a_new_seed_a_new_selection(self):
        again, _, selection = build()
        self.assertEqual([c["id"] for c in again["cases"]], [c["id"] for c in self.suite["cases"]])
        self.assertEqual(selection["id"], self.selection["id"])
        other_suite, _, other = build(seed=7)
        self.assertNotEqual(other["id"], self.selection["id"])
        self.assertFalse({c["id"] for c in other_suite["cases"]} & set(self.cases))

    def test_lane_order_is_a_seeded_shuffle_and_the_planner_keeps_it(self):
        ids = lane_ids(self.bundles["lane2"])
        self.assertNotEqual(ids, sorted(ids))
        _, reseeded, _ = build(seed=99)
        names = lambda listing: [i.split("-", 2)[-1] for i in listing]
        self.assertNotEqual(names(lane_ids(reseeded["lane2"])), names(ids))
        with tempfile.TemporaryDirectory() as scratch:
            suite_path = Path(scratch) / "suite.json"
            suite_path.write_text(json.dumps(self.suite), encoding="utf-8")
            bundle = {"suites": [{**self.bundles["lane2"]["suites"][0], "path": str(suite_path)}]}
            bundle_path = Path(scratch) / "bundle.json"
            bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
            jobs = runner.load_jobs(bundle_path, REGISTRY)
        self.assertEqual([job["case"]["id"] for job in jobs], ids)
        self.assertTrue(all(job["execution"] == "concurrent" for job in jobs))

    def test_a_replicate_never_reuses_an_id_for_a_different_request(self):
        requests = {}
        for case in self.cases.values():
            body = {k: v for k, v in case.items() if k not in {"id", "state", "environment", "eligibility", "release_gate"}}
            requests.setdefault(gate.canonical(body), []).append(case["id"])
        self.assertTrue(any(len(ids) > 1 for ids in requests.values()))  # replicates exist
        for ids in requests.values():
            self.assertEqual(len({i.rsplit("-r", 1)[0] for i in ids}), 1)  # one request, one name, numbered
        self.assertEqual(len(self.cases), sum(len(ids) for ids in requests.values()))

    def test_bundles_carry_the_profile_and_commands_name_the_lane_phones(self):
        commands = gate.commands(self.selection)
        for lane, bundle in self.bundles.items():
            self.assertEqual(bundle["suites"][0]["execution"], "concurrent")
            self.assertIn("required_families", bundle["suites"][0])
            self.assertIn(",".join(bundle["phones"]), commands[lane]["plan"])
            self.assertIn("--parallel 1", commands[lane]["run"])
            self.assertNotIn("--state", commands[lane]["run"])  # the state queue would undo the random order

    def test_a_different_equivalents_table_is_a_different_selection(self):
        import tempfile
        import user_launch
        table = json.loads((ROOT / "tools/video_default_equivalents.json").read_text(encoding="utf-8"))
        self.assertEqual(user_launch.equivalents_sha256(), self.selection["video_default_equivalents"]["sha256"])
        with tempfile.TemporaryDirectory() as directory:
            same, other = Path(directory) / "same.json", Path(directory) / "other.json"
            same.write_text(json.dumps(table, indent=4).replace(chr(10), chr(13) + chr(10)),
                            encoding="utf-8", newline="")  # same content, other indentation and line endings
            table["phones"]["s24"]["video_width"] = 640
            other.write_text(json.dumps(table), encoding="utf-8")
            _, _, kept = build(equivalents_path=same)
            _, _, changed = build(equivalents_path=other)
        self.assertEqual(self.selection["id"], kept["id"])
        self.assertNotEqual(self.selection["id"], changed["id"])


if __name__ == "__main__":
    unittest.main()
