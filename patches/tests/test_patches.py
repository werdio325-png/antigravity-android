#!/usr/bin/env python3
"""Tests for the restructured patch pipeline (stdlib unittest only)."""

import importlib
import os
import sys
import tempfile
import unittest

PATCHES_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PATCHES_DIR not in sys.path:
    sys.path.insert(0, PATCHES_DIR)

from patch_lib import loader, manifest, module_base, scan  # noqa: E402
from patch_lib import runner  # noqa: E402
from groups.resolv.selftest import _selftest as resolv_selftest  # noqa: E402
from groups.auth.selftest import _selftest as auth_selftest  # noqa: E402
from groups.resolv import signatures as resolv_sig  # noqa: E402
from groups.resolv import analyze as resolv_analyze  # noqa: E402
from groups.auth import signatures as auth_sig  # noqa: E402
from groups.auth import analyze as auth_analyze  # noqa: E402
from groups.gates import analyze as gates_analyze  # noqa: E402


class RunnerContractTest(unittest.TestCase):
    def test_group_modules(self):
        self.assertEqual(runner.GROUP_MODULES, [
            ("eligibility", "gates"),
            ("seccomp", "syscalls"),
            ("resolv", "resolv"),
        ])

    def test_every_group_exposes_run(self):
        for _gid, name in runner.GROUP_MODULES:
            mod = loader.load_group(name)
            self.assertTrue(callable(mod.run), name)


class ModuleBaseTest(unittest.TestCase):
    def test_parse_group_argv(self):
        opts = module_base.parse_group_argv(
            ["bin", "--verify", "--dry-run", "--allow-missing"])
        self.assertEqual(opts, {"verify": True, "dry_run": True,
                                "allow_missing": True})
        self.assertEqual(module_base.parse_group_argv(["bin"]),
                         {"verify": False, "dry_run": False,
                          "allow_missing": False})

    def test_read_target_missing(self):
        report = {"messages": []}
        self.assertIsNone(module_base.read_target("/no/such/file", report))
        self.assertEqual(report["messages"], ["target not found: /no/such/file"])


class ScanTest(unittest.TestCase):
    def test_movz_x0(self):
        self.assertEqual(scan.movz_x0(439), b"\xe0\x36\x80\xd2")
        self.assertEqual(scan.movz_x0(48), b"\x00\x06\x80\xd2")
        self.assertEqual(scan.movz_x0(452), b"\x80\x38\x80\xd2")
        self.assertEqual(scan.movz_x0(53), b"\xa0\x06\x80\xd2")

    def test_patch_body_states(self):
        body = scan.movz_x0(439)
        self.assertEqual(scan._patch_body(body, 439, 48)[0], "patch")
        self.assertEqual(scan._patch_body(scan.movz_x0(48), 439, 48)[0], "already")
        self.assertEqual(scan._patch_body(b"", 439, 48)[0], "empty")
        self.assertEqual(
            scan._patch_body(body + body, 439, 48)[0], "ambiguous")


class ManifestTest(unittest.TestCase):
    def test_extract_core_version(self):
        self.assertEqual(manifest.extract_core_version(b"\x00" * 16), "unknown")
        data = b"junk" + b"\xff Go buildinf:" + b"go1.28-20260721-RC03\x00"
        self.assertEqual(manifest.extract_core_version(data),
                         "go1.28-20260721-RC03")

    def test_stable_ignores_generated_at(self):
        a = {"generated_at": "x", "ok": True}
        b = {"generated_at": "y", "ok": True}
        self.assertEqual(manifest._stable(a), manifest._stable(b))


class GatesTest(unittest.TestCase):
    def test_analyze_empty(self):
        st = gates_analyze.analyze(b"")
        self.assertEqual(st, {"raw_cli": [], "raw_mgr": [],
                              "pat_cli": [], "pat_mgr": []})


class ResolvTest(unittest.TestCase):
    def test_selftest(self):
        self.assertTrue(resolv_selftest())

    def test_analyze_and_apply(self):
        data = bytearray(b"a" + resolv_sig.TARGET + b"b" + resolv_sig.TARGET)
        clean, rejected, repl = resolv_analyze.analyze(data)
        self.assertEqual((len(clean), len(rejected), len(repl)), (2, 0, 0))
        n = resolv_analyze.apply_inplace(data)
        self.assertEqual(n[0], 2)
        self.assertEqual(bytes(data).count(resolv_sig.REPLACEMENT), 2)


class AuthTest(unittest.TestCase):
    def test_selftest(self):
        self.assertTrue(auth_selftest())

    def test_lengths(self):
        self.assertEqual(len(auth_sig.TARGET), 46)
        self.assertEqual(len(auth_sig.REPLACEMENT), 46)


class LoaderTest(unittest.TestCase):
    def test_load_module_by_path(self):
        fd, path = tempfile.mkstemp(suffix=".py")
        try:
            with os.fdopen(fd, "w") as f:
                f.write("VALUE = 42\n")
            mod = loader.load_module(path)
            self.assertEqual(mod.VALUE, 42)
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
