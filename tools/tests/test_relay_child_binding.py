"""A user-launch relayed call names its callee by a per-call pseudonym; only the callee's own log can bind it.

The rows below have the shape of the saved lane runs (a helper's accepted SETUP naming the callee's rotating ID, its join
line naming the pseudonym, and the callee's own link, relayed_cap and ring lines). A wrong target, a callee that never
rang as a relayed callee or an older epoch's ID must not bind.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parent))
import run_private_call_benchmark as runner
from score_device_run import records
from test_user_launch import Fixture

PSEUDONYM = "67c1c1a083d3"
CALLEE_ID = "c36e43222da8"
PARENT = "05697ceb0834"


def rows(*lines):
    text = "\n".join(f"11-10 17:{minute:02d}:{second:06.3f} 1 1 I BluetoothVideo: {message}"
                     for minute, second, message in lines)
    return records(text)


def relay_rows(target=CALLEE_ID, setup_second=3.8, join_second=7.3):
    return rows((50, setup_second, "BENCHMARK relayed_setup: role=helper | result=accepted | budget=1 | next_budget=0 | "
                                    f"next=target:{target}"),
                (50, join_second, f"Relaying an endpoint-encrypted private call between {PARENT} and {PSEUDONYM}; "
                                  "this relay cannot decrypt call media."))


def callee_rows(rotid=CALLEE_ID, hops=1, ring=True, link=True, extra=()):
    lines = [(48, 48.0, f"BENCHMARK rotid_own: role=call | epoch=1993681 | rdi={rotid}")]
    if link:
        lines.append((50, 8.682, "Encrypted private-call link to an unverified stranger over BLE GATT (PHY TX 1M / RX 1M)"))
    if hops:
        lines.append((50, 8.707, f"BENCHMARK call relayed_cap: relayHops={hops} | measuredCap=false | budgetKbps=none | fps=15 | "
                                  "bitrateKbps=110"))
    lines += list(extra)
    if ring:
        lines.append((50, 8.733, "BENCHMARK call phase INCOMING_RINGING"))
    return rows(*lines)


def bind(relay=None, callee=None, child=PSEUDONYM, hops=1):
    relay = relay if relay is not None else relay_rows()
    return runner.bind_relay_child(child, relay, callee if callee is not None else callee_rows(), hops,
                                   relay[-1]["at"])


class BindRelayChildTest(unittest.TestCase):
    def test_the_target_the_helper_was_given_is_the_callees_own_id_and_the_callee_rang_as_a_relayed_callee(self):
        found = bind()
        self.assertEqual("rotid_target", found["method"])
        self.assertEqual((CALLEE_ID, PSEUDONYM), (found["target"], found["child"]))

    def test_a_target_that_is_not_the_callees_own_id_does_not_bind(self):
        found = bind(relay=relay_rows(target="aaaaaaaaaaaa"))
        self.assertIsNone(found["method"])
        self.assertIn("aaaaaaaaaaaa", found["reason"])

    def test_the_callee_must_have_rung(self):
        self.assertIsNone(bind(callee=callee_rows(ring=False))["method"])

    def test_the_ring_must_be_a_relayed_one(self):
        self.assertIsNone(bind(callee=callee_rows(hops=0))["method"])  # no relayed_cap line: a direct call
        self.assertIsNone(bind(callee=callee_rows(hops=2))["method"])  # another hop count than the requested one

    def test_the_callees_own_link_line_is_required(self):
        self.assertIsNone(bind(callee=callee_rows(link=False))["method"])

    def test_an_id_from_an_older_epoch_does_not_bind(self):
        newer = callee_rows(extra=[(50, 5.0, "BENCHMARK rotid_own: role=call | epoch=1993682 | rdi=bbbbbbbbbbbb")])
        self.assertIsNone(bind(callee=newer)["method"])

    def test_a_ring_long_after_the_join_window_does_not_bind(self):
        late = rows((48, 48.0, f"BENCHMARK rotid_own: role=call | epoch=1993681 | rdi={CALLEE_ID}"),
                    (50, 8.707, "BENCHMARK call relayed_cap: relayHops=1 | measuredCap=false"),
                    (50, 8.682, "Encrypted private-call link to an unverified stranger over BLE GATT"),
                    (52, 30.0, "BENCHMARK call phase INCOMING_RINGING"))
        self.assertIsNone(bind(callee=late)["method"])

    def test_a_setup_the_helper_accepted_after_it_joined_is_not_the_one_that_joined(self):
        relay = rows((50, 7.3, f"Relaying an endpoint-encrypted private call between {PARENT} and {PSEUDONYM}; x"),
                     (50, 9.0, f"BENCHMARK relayed_setup: role=helper | result=accepted | budget=1 | next_budget=0 | "
                               f"next=target:{CALLEE_ID}"))
        self.assertIsNone(runner.bind_relay_child(PSEUDONYM, relay, callee_rows(), 1, relay[0]["at"])["method"])

    def test_the_apps_own_pseudonym_line_binds_more_strongly_and_needs_no_setup(self):
        line = (50, 8.69, f"BENCHMARK pseudonym_own: role=call | link=hop1 | id={PSEUDONYM}")
        found = bind(relay=relay_rows(target="aaaaaaaaaaaa"), callee=callee_rows(extra=[line]))
        self.assertEqual("pseudonym_own", found["method"])
        self.assertIn(PSEUDONYM, found["line"])

    def test_another_pseudonym_does_not_bind_by_the_apps_line(self):
        line = (50, 8.69, "BENCHMARK pseudonym_own: role=call | link=hop1 | id=000000000099")
        self.assertIsNone(bind(relay=relay_rows(target="aaaaaaaaaaaa"), callee=callee_rows(extra=[line]))["method"])


class ScoreRelayHopTest(Fixture, unittest.TestCase):
    """The scorer binds the join line's child the same way, from the relay's and the callee's own saved lines."""

    def hop(self, relay, callee):
        case = self.case(relay="r", relay_shape="on_demand")
        manifest = {"scenario": case, "devices": {p: {"node_prefix": v["node_prefix"]} for p, v in self.devices.items()}}
        scope = {"spec": {"device": "r", "pid": 1, "start": None, "path": "r.log"}, "rows": relay}
        return runner.score_relay_hop(manifest, 0, [scope], {"a": [], "b": callee}, None, {}, {})

    def test_a_pseudonymous_child_bound_by_the_callees_own_log_passes_and_records_the_method(self):
        hop = self.hop(relay_rows(), callee_rows())
        self.assertEqual("PASS", hop["gates"]["relay_adjacent_child_identity"]["status"])
        self.assertEqual("rotid_target", hop["relay_child_binding"][PSEUDONYM]["method"])

    def test_an_unbound_child_fails_the_child_identity_gate(self):
        for relay, callee in ((relay_rows(target="aaaaaaaaaaaa"), callee_rows()), (relay_rows(), callee_rows(ring=False))):
            hop = self.hop(relay, callee)
            self.assertEqual("FAIL", hop["gates"]["relay_adjacent_child_identity"]["status"])
            self.assertIsNone(hop["relay_child_binding"][PSEUDONYM]["method"])

    def test_a_child_that_is_the_requested_prefix_needs_no_binding(self):
        relay = rows((50, 7.3, f"Relaying an endpoint-encrypted private call between {PARENT} and {self.devices['b']['node_prefix']}; x"))
        hop = self.hop(relay, [])
        self.assertEqual("PASS", hop["gates"]["relay_adjacent_child_identity"]["status"])
        self.assertNotIn("relay_child_binding", hop)


class RunnerJoinTest(Fixture, unittest.TestCase):
    """The runner's own join check (confirm_relayed_join) with the saved-run lines of the relay and the callee."""

    @staticmethod
    def render(items):
        return chr(10).join(f"11-10 17:{minute:02d}:{second:06.3f} 1 1 I BluetoothVideo: {message}"
                         for minute, second, message in items)

    def stage(self, relay_text, callee_text):
        instance = object.__new__(runner.CallRunner)
        instance.case = self.case(relay="r", relay_shape="on_demand", route_wait_s=0)
        instance.relays, instance.devices = ["r"], self.devices
        instance.relay_events, instance.child_bindings = [], {}
        logs = {"r": relay_text, "b": callee_text}
        instance.log_text = lambda phone: logs[phone]
        join = next(row for row in records(relay_text) if "Relaying" in row["message"])
        instance.relay_wait = lambda *args, **kwargs: join
        return instance

    RELAY = [(50, 3.8, "BENCHMARK relayed_setup: role=helper | result=accepted | budget=1 | next_budget=0 | next=target:{target}"),
             (50, 7.3, f"Relaying an endpoint-encrypted private call between {PARENT} and {PSEUDONYM}; this relay cannot decrypt call media.")]
    CALLEE = [(48, 48.0, f"BENCHMARK rotid_own: role=call | epoch=1993681 | rdi={CALLEE_ID}"),
              (50, 8.682, "Encrypted private-call link to an unverified stranger over BLE GATT"),
              (50, 8.707, "BENCHMARK call relayed_cap: relayHops=1 | measuredCap=false"),
              (50, 8.733, "BENCHMARK call phase INCOMING_RINGING")]

    def run_join(self, target=CALLEE_ID, callee=None):
        relay = [(m, s, t.format(target=target)) for m, s, t in self.RELAY]
        instance = self.stage(self.render(relay), self.render(callee if callee is not None else self.CALLEE))
        instance.confirm_relayed_join("a", "b")
        return instance

    def test_a_pseudonymous_child_bound_by_the_callee_passes_and_the_method_is_recorded(self):
        instance = self.run_join()
        self.assertEqual({"r": "rotid_target"}, instance.child_bindings)
        self.assertEqual("rotid_target", instance.relay_events[-1]["relay_child_binding"])

    def test_a_wrong_target_stops_the_run_before_the_call_is_trusted(self):
        with self.assertRaisesRegex(RuntimeError, "not bound to the requested child b"):
            self.run_join(target="aaaaaaaaaaaa")

    def test_a_callee_that_never_rang_stops_the_run(self):
        with self.assertRaisesRegex(RuntimeError, "no incoming ring"):
            self.run_join(callee=self.CALLEE[:3])


if __name__ == "__main__":
    unittest.main()
