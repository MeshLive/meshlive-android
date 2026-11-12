"""After a call the runner proves each phone stopped MeshLive before reassigning it; offline, no phone operations."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_benchmark_suite as suite


class FakeLease:
    def __init__(self):
        self.quarantined = []

    def quarantine_phone(self, phone, reason, path):
        self.quarantined.append(phone)


def fake_adb(pid_answers):
    """pidof answers in order (a pid or ""); records every force-stop."""
    calls = {"force_stops": 0}
    answers = iter(pid_answers)

    def run(command, **_):
        if "pidof" in command:
            return subprocess.CompletedProcess(command, 0 if (pid := next(answers, "")) else 1, pid, "")
        if "force-stop" in command:
            calls["force_stops"] += 1
            return subprocess.CompletedProcess(command, 0, "", "")
        raise AssertionError(command)
    return run, calls


class PhoneCleanupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="phone-cleanup-")
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.devices = {"a15s": {"serial": "SERIAL"}}

    def verify(self, answers):
        lease = FakeLease()
        run, calls = fake_adb(answers)
        with patch.object(suite.subprocess, "run", run):
            try:
                suite.verify_phone_cleanup("adb", self.devices, ["a15s"], self.output, lease, sleep=lambda _: None)
                raised = False
            except RuntimeError:
                raised = True
        return lease, calls, raised

    def test_a_process_still_exiting_after_the_call_is_stopped_again_and_the_phone_stays_usable(self):
        lease, calls, raised = self.verify(["6452", ""])
        self.assertFalse(raised)
        self.assertEqual([], lease.quarantined)
        self.assertEqual(1, calls["force_stops"])

    def test_a_phone_whose_app_will_not_stop_is_still_quarantined(self):
        lease, calls, raised = self.verify(["6452"] * suite.CLEANUP_PROBES)
        self.assertTrue(raised)
        self.assertEqual(["a15s"], lease.quarantined)

    def test_a_phone_that_already_stopped_is_not_force_stopped(self):
        lease, calls, raised = self.verify([""])
        self.assertFalse(raised)
        self.assertEqual(0, calls["force_stops"])


if __name__ == "__main__":
    unittest.main()
