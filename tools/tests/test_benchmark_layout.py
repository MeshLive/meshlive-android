import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_layout as layout


class BenchmarkLayoutTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="benchmark-layout-test-")
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name).resolve()
        self.ledger = self.root / "benchmarks/catalog/ledger.json"
        self.ledger.parent.mkdir(parents=True)
        self.paths = {"artifacts/old/run/manifest.json": "benchmarks/runs/run-id/manifest.json",
                      "artifacts/old/run/sender.log": "benchmarks/runs/run-id/logs/sender.log",
                      "artifacts/old/reference.yuv": "benchmarks/inputs/source-id/native.yuv"}
        self.save()

    def save(self, paths=None, directories=None):
        self.ledger.write_text(json.dumps({"storage": {
            "paths": self.paths if paths is None else paths,
            "directories": directories or {}}}), encoding="utf-8")

    def test_output_on_an_unplugged_evidence_drive_says_to_plug_it_in(self):
        target = self.root / "ssd/runs"
        target.mkdir(parents=True)
        import subprocess
        subprocess.run(["cmd", "/c", "mklink", "/J", str(self.root / "benchmarks/runs"), str(target)], check=True,
                       capture_output=True)
        target.rmdir()  # the drive is gone; the junction dangles
        with self.assertRaisesRegex(ValueError, "plug it in"):
            layout.output_path("benchmarks/runs/new-run", "runs", root=self.root)
        self.assertEqual(layout.output_path("benchmarks/catalog/plan", "catalog", root=self.root),
                         self.root / "benchmarks/catalog/plan")

    def test_absolute_relative_and_windows_separator_aliases(self):
        expected = self.root / "benchmarks/runs/run-id/manifest.json"
        for path in ("artifacts/old/run/manifest.json", self.root / "artifacts/old/run/manifest.json"):
            self.assertEqual(expected, layout.resolve(path, root=self.root))
        self.save({"artifacts\\old\\run\\manifest.json": "benchmarks\\runs\\run-id\\manifest.json"})
        self.assertEqual(expected, layout.resolve("artifacts/old/run/manifest.json", root=self.root))

    def test_relative_evidence_uses_original_document_base_without_rewriting_request(self):
        manifest_path = self.root / self.paths["artifacts/old/run/manifest.json"]
        manifest_path.parent.mkdir(parents=True)
        request = {"raw_fixture_file": "artifacts/old/reference.yuv", "logs": [{"path": "sender.log"}]}
        encoded = json.dumps(request, sort_keys=True).encode()
        manifest_path.write_bytes(encoded)
        before = hashlib.sha256(encoded).hexdigest()
        loaded = json.loads(manifest_path.read_text())
        base = layout.original(manifest_path, root=self.root).parent
        self.assertEqual(self.root / "artifacts/old/run", base)
        self.assertEqual(self.root / "benchmarks/runs/run-id/logs/sender.log",
                         layout.resolve(loaded["logs"][0]["path"], base=base, root=self.root))
        self.assertEqual(self.root / "benchmarks/inputs/source-id/native.yuv",
                         layout.resolve(loaded["raw_fixture_file"], root=self.root))
        self.assertEqual(request, loaded)
        self.assertEqual(before, hashlib.sha256(manifest_path.read_bytes()).hexdigest())

    def test_directory_alias_is_exact_and_does_not_invent_descendant_aliases(self):
        self.save(directories={"artifacts/old/run": "benchmarks/runs/run-id"})
        self.assertEqual(self.root / "benchmarks/runs/run-id", layout.resolve("artifacts/old/run", root=self.root))
        self.assertEqual(self.root / "artifacts/old/run/unlisted.log",
                         layout.resolve("artifacts/old/run/unlisted.log", root=self.root))

    def test_canonical_unmapped_and_external_paths_are_unchanged(self):
        for path in (self.root / "benchmarks/inputs/source-id/native.yuv", self.root / "other.json", self.root.parent / "external.json"):
            self.assertEqual(path, layout.resolve(path, root=self.root))
        self.assertEqual(self.root / "artifacts/old/run/manifest.json",
                         layout.original("benchmarks/runs/run-id/manifest.json", root=self.root))

    def test_unrelated_equal_bytes_do_not_create_aliases(self):
        for name in ("a.log", "b.log"):
            (self.root / name).write_bytes(b"same bytes")
        self.assertNotEqual(layout.resolve("a.log", root=self.root), layout.resolve("b.log", root=self.root))

    def test_unsafe_alias_locators_and_targets_are_rejected(self):
        for source, target in (("../old.json", "benchmarks/catalog/a.json"),
                               ("/old.json", "benchmarks/catalog/a.json"),
                               ("C:\\old.json", "benchmarks/catalog/a.json"),
                               ("artifacts/a.json", "benchmarks/runs/../inputs/a.json"),
                               ("artifacts/a.json", "other/a.json"),
                               ("artifacts/a.json", "benchmarks/catalog"),
                               ("artifacts/a.json", "benchmarks/runs/a.json:stream")):
            with self.subTest(source=source, target=target):
                self.save({source: target})
                with self.assertRaises(ValueError):
                    layout.resolve("artifacts/a.json", root=self.root)

    def test_alias_collisions_and_canonical_redirection_are_rejected(self):
        mappings = ({"artifacts/a.json": "benchmarks/catalog/a.json", "artifacts/b.json": "benchmarks/catalog/a.json"},
                    {"artifacts/a.json": "benchmarks/catalog/a.json", "benchmarks/catalog/a.json": "benchmarks/catalog/b.json"})
        for paths in mappings:
            self.save(paths)
            with self.assertRaisesRegex(ValueError, "collide|redirect"):
                layout.resolve("artifacts/a.json", root=self.root)
        self.save({"artifacts/a.json": "benchmarks/catalog/a.json"}, {"artifacts/a.json": "benchmarks/runs/a"})
        with self.assertRaisesRegex(ValueError, "collide"):
            layout.original("benchmarks/catalog/a.json", root=self.root)

    def test_alias_cannot_escape_through_a_link_inside_the_group(self):
        parent = self.root / "benchmarks/runs"
        parent.mkdir(parents=True)
        link = parent / "linked"
        try:
            link.symlink_to(self.root.parent, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Host cannot create symlinks: {error}")
        self.save({"artifacts/a.json": "benchmarks/runs/linked/unowned.json"})
        with self.assertRaisesRegex(ValueError, "escapes"):
            layout.resolve("artifacts/a.json", root=self.root)

    def test_mapping_cache_reads_once_and_refreshes_after_checkpoint(self):
        real_read = Path.read_text
        with patch.object(Path, "read_text", autospec=True, side_effect=real_read) as read:
            layout.resolve("artifacts/old/run/manifest.json", root=self.root)
            layout.original("benchmarks/runs/run-id/manifest.json", root=self.root)
            self.assertEqual(1, read.call_count)
            self.paths["artifacts/old/run/manifest.json"] = "benchmarks/runs/changed-id/manifest.json"
            self.save()
            self.assertEqual(self.root / "benchmarks/runs/changed-id/manifest.json",
                             layout.resolve("artifacts/old/run/manifest.json", root=self.root))
            self.assertEqual(2, read.call_count)

    def test_mapping_cache_refreshes_on_mtime_when_size_is_unchanged(self):
        source = "artifacts/old/run/manifest.json"
        layout.resolve(source, root=self.root)
        before = self.ledger.stat()
        self.paths[source] = "benchmarks/runs/new-id/manifest.json"
        self.save()
        self.assertEqual(before.st_size, self.ledger.stat().st_size)
        os.utime(self.ledger, ns=(before.st_atime_ns, before.st_mtime_ns + 1_000_000_000))
        self.assertEqual(self.root / self.paths[source], layout.resolve(source, root=self.root))

    def test_newly_created_ledger_replaces_empty_cache(self):
        self.ledger.unlink()
        old = "artifacts/old/run/manifest.json"
        self.assertEqual(self.root / old, layout.resolve(old, root=self.root))
        self.save()
        self.assertEqual(self.root / self.paths[old], layout.resolve(old, root=self.root))

    def test_output_uses_expected_group_without_reading_aliases(self):
        self.ledger.write_text("invalid ledger", encoding="utf-8")
        for group in layout.GROUPS:
            path = self.root / "benchmarks" / group / "identity/result.json"
            self.assertEqual(path, layout.output_path(path, group, root=self.root))
        self.assertEqual(self.ledger.parent, layout.output_path("benchmarks/catalog", "catalog", root=self.root))

    def test_outputs_reject_old_trees_wrong_groups_and_group_roots(self):
        for path in ("artifacts/old/run", "benchmarks/checks/prompt", "docs/new.json", "new.log", "benchmarks/inputs/id", "benchmarks/runs"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                layout.output_path(path, "runs", root=self.root)

    def test_temporary_scratch_is_allowed_other_external_output_is_rejected(self):
        scratch = Path(tempfile.gettempdir()).resolve() / "layout-owned-scratch/results.json"
        self.assertEqual(scratch, layout.output_path(scratch, "runs", root=self.root))
        with self.assertRaises(ValueError):
            layout.output_path(Path.home() / "unowned-layout-output.json", "runs", root=self.root)

    def test_output_rejects_links_before_following_them(self):
        parent = self.root / "benchmarks/runs"
        parent.mkdir(parents=True)
        link = parent / "linked"
        try:
            link.symlink_to(self.root.parent, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Host cannot create symlinks: {error}")
        with self.assertRaisesRegex(ValueError, "link|junction"):
            layout.output_path(link / "unowned.json", "runs", root=self.root)


if __name__ == "__main__":
    unittest.main()
