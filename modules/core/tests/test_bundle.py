#!/usr/bin/env python3
"""
test_bundle.py - Unit test for assembling the entire runtime assets directory.
"""
import unittest
import os
import sys
import tempfile
import shutil

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
MODULE_ROOT = os.path.dirname(TESTS_DIR)
sys.path.insert(0, os.path.join(MODULE_ROOT, "bundle"))
sys.path.insert(0, os.path.join(MODULE_ROOT, "unpack"))
sys.path.insert(0, os.path.join(MODULE_ROOT, "manifest"))

import assemble
import extractor
import manifest_verifier

class TestBundle(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.archive_path = os.path.join(MODULE_ROOT, "pristine", "language_server.tar.gz")

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_01_dry_run_assembly(self):
        dest_bin = os.path.join(self.tmp_dir, "staged_core")
        # Extract core first
        extractor.extract_core(self.archive_path, dest_bin)
        
        # Test dry-run
        res = assemble.assemble_runtime(dest_bin, MODULE_ROOT, os.path.join(self.tmp_dir, "rt"), dry_run=True)
        self.assertTrue(res.get("dry_run"))

    def test_02_full_assembly(self):
        dest_bin = os.path.join(self.tmp_dir, "staged_core")
        extractor.extract_core(self.archive_path, dest_bin)

        rt_dir = os.path.join(self.tmp_dir, "rt")
        manifest = assemble.assemble_runtime(dest_bin, MODULE_ROOT, rt_dir, dry_run=False)
        
        self.assertIn("files", manifest)
        self.assertGreater(len(manifest["files"]), 15)

        # Ensure key files are present
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "bin", "liblanguage_server.so")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "bin", "libloader.so")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "bin", "ld-linux-aarch64.so.1")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "bin", "xdg-open")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "bin", "rish")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "lib", "libc.so.6")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "certs", "ca-certificates.crt")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "seed", "AGENTS.md")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "etc", "hosts")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "etc", "resolv.conf")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "etc", "nsswitch.conf")))
        self.assertTrue(os.path.isfile(os.path.join(rt_dir, "manifest.json")))

        # Verify manifest matches assembled runtime
        manifest_path = os.path.join(rt_dir, "manifest.json")
        self.assertTrue(manifest_verifier.verify_manifest(manifest_path, rt_dir))

        # Regression check: no CRLF (\r) in any packed shell shim, etc config, cert, or seed text file
        binary_bins = {"liblanguage_server.so", "libloader.so", "ld-linux-aarch64.so.1", "rish_shizuku.dex"}
        for sub in ("bin", "etc", "certs", "seed"):
            sub_dir = os.path.join(rt_dir, sub)
            for r, _, fns in os.walk(sub_dir):
                for fn in fns:
                    if sub == "bin" and fn in binary_bins:
                        continue
                    fp = os.path.join(r, fn)
                    if os.path.islink(fp):
                        continue
                    with open(fp, "rb") as f:
                        self.assertNotIn(b"\r", f.read(), f"CRLF found in packed runtime file: {sub}/{fn}")

if __name__ == "__main__":
    unittest.main()
