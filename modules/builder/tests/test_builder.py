#!/usr/bin/env python3
"""
test_builder.py - Unit tests for modules/builder components.
"""
import unittest
import tempfile
import zipfile
import shutil
import sys
import os

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
BUILDER_DIR = os.path.dirname(TESTS_DIR)
sys.path.insert(0, BUILDER_DIR)

import align


class TestBuilder(unittest.TestCase):

    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="test_builder_")

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_python_zipalign(self):
        raw_zip = os.path.join(self.tmp_dir, "raw.zip")
        aligned_zip = os.path.join(self.tmp_dir, "aligned.zip")

        # Create an unaligned zip with uncompressed entries of odd lengths
        with zipfile.ZipFile(raw_zip, "w") as z:
            z.writestr("test1.txt", "123", compress_type=zipfile.ZIP_STORED)
            z.writestr("test2.bin", "1234567", compress_type=zipfile.ZIP_STORED)
            z.writestr("compressed.dat", "Hello world compressed content", compress_type=zipfile.ZIP_DEFLATED)

        # Run pure Python 4-byte aligner
        ok = align.align_with_python(raw_zip, aligned_zip, alignment=4)
        self.assertTrue(ok)
        self.assertTrue(os.path.isfile(aligned_zip))

        # Inspect offsets in aligned zip
        with open(aligned_zip, "rb") as f:
            data = f.read()

        with zipfile.ZipFile(aligned_zip, "r") as z:
            self.assertIsNone(z.testzip(), "CRC-32 verification failed on aligned ZIP!")
            self.assertEqual(z.read("test1.txt"), b"123")
            self.assertEqual(z.read("test2.bin"), b"1234567")
            self.assertEqual(z.read("compressed.dat"), b"Hello world compressed content")
            for info in z.infolist():
                if info.compress_type == 0:
                    # In a local header: fixed 30 bytes + len(name) + len(extra)
                    # We verify data start offset is a multiple of 4
                    header_offset = info.header_offset
                    data_offset = header_offset + 30 + len(info.filename.encode("utf-8")) + len(info.extra)
                    self.assertEqual(data_offset % 4, 0, f"Entry {info.filename} at offset {data_offset} not 4-byte aligned!")

    def test_01_gen_config_build_dir(self):
        import subprocess
        project_root = os.path.abspath(os.path.join(BUILDER_DIR, "..", ".."))
        build_dir = os.path.join(self.tmp_dir, "build")
        cmd = [sys.executable, os.path.join(BUILDER_DIR, "01_gen_config.py"), build_dir]
        res = subprocess.run(cmd, capture_output=True, text=True, cwd=project_root)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "res", "values", "gen.xml")))
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "gen", "dev", "continuum", "antigravity", "BuildConfig.java")))
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "AndroidManifest.xml")))

    def test_02_r_java_overlay_and_dedup(self):
        import subprocess
        project_root = os.path.abspath(os.path.join(BUILDER_DIR, "..", ".."))
        tools_dir = os.path.join(project_root, "tools")
        android_jar = os.path.join(tools_dir, "android.jar")
        res_dir = os.path.join(project_root, "app", "src", "main", "res")

        # Test release mode (dev.continuum.antigravity) -> single call
        build_dir = os.path.join(self.tmp_dir, "build_rel")
        cmd_cfg = [sys.executable, os.path.join(BUILDER_DIR, "01_gen_config.py"), build_dir]
        subprocess.run(cmd_cfg, check=True, cwd=project_root)
        gen_dir = os.path.join(build_dir, "gen")
        manifest = os.path.join(build_dir, "AndroidManifest.xml")
        overlay_res = os.path.join(build_dir, "res")

        cmd_r = ["bash", os.path.join(BUILDER_DIR, "02_r_java.sh"), gen_dir, manifest, res_dir, android_jar, overlay_res]
        res = subprocess.run(cmd_r, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertNotIn("Generating fallback dev.continuum.antigravity.R", res.stdout)
        self.assertTrue(os.path.isfile(os.path.join(gen_dir, "dev", "continuum", "antigravity", "R.java")))

        # Test dev mode (dev.continuum.antigravity.dev) -> generates both dev.continuum.antigravity.dev.R and fallback dev.continuum.antigravity.R
        build_dir_dev = os.path.join(self.tmp_dir, "build_dev")
        subprocess.run(cmd_cfg[:2] + [build_dir_dev], check=True, cwd=project_root, env={**os.environ, "DEV_MODE": "1"})
        gen_dir_dev = os.path.join(build_dir_dev, "gen")
        manifest_dev = os.path.join(build_dir_dev, "AndroidManifest.xml")
        overlay_res_dev = os.path.join(build_dir_dev, "res")

        cmd_r_dev = ["bash", os.path.join(BUILDER_DIR, "02_r_java.sh"), gen_dir_dev, manifest_dev, res_dir, android_jar, overlay_res_dev]
        res_dev = subprocess.run(cmd_r_dev, capture_output=True, text=True)
        self.assertEqual(res_dev.returncode, 0, res_dev.stderr)
        self.assertIn("Generating fallback dev.continuum.antigravity.R", res_dev.stdout)
        self.assertTrue(os.path.isfile(os.path.join(gen_dir_dev, "dev", "continuum", "antigravity", "R.java")))
        self.assertTrue(os.path.isfile(os.path.join(gen_dir_dev, "dev", "continuum", "antigravity", "dev", "R.java")))

    def test_03_and_04_compile_and_dex_argfile(self):
        import subprocess
        project_root = os.path.abspath(os.path.join(BUILDER_DIR, "..", ".."))
        tools_dir = os.path.join(project_root, "tools")
        android_jar = os.path.join(tools_dir, "android.jar")
        r8_jar = os.path.join(tools_dir, "r8.jar")

        build_dir = os.path.join(self.tmp_dir, "build_pipeline")
        subprocess.run([sys.executable, os.path.join(BUILDER_DIR, "01_gen_config.py"), build_dir], check=True, cwd=project_root)
        gen_dir = os.path.join(build_dir, "gen")
        manifest = os.path.join(build_dir, "AndroidManifest.xml")
        res_dir = os.path.join(project_root, "app", "src", "main", "res")
        overlay_res = os.path.join(build_dir, "res")
        subprocess.run(["bash", os.path.join(BUILDER_DIR, "02_r_java.sh"), gen_dir, manifest, res_dir, android_jar, overlay_res], check=True)

        obj_dir = os.path.join(build_dir, "obj")
        src_dir = os.path.join(project_root, "app", "src", "main", "java")
        cmd_comp = ["bash", os.path.join(BUILDER_DIR, "03_compile_java.sh"), obj_dir, gen_dir, src_dir, android_jar]
        res_comp = subprocess.run(cmd_comp, capture_output=True, text=True, env={**os.environ, "BUILD_DIR": build_dir})
        self.assertEqual(res_comp.returncode, 0, res_comp.stderr)
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "java_sources.txt")))

        dex_dir = os.path.join(build_dir, "dex")
        cmd_dex = ["bash", os.path.join(BUILDER_DIR, "04_dex.sh"), dex_dir, obj_dir, r8_jar, android_jar, "24"]
        res_dex = subprocess.run(cmd_dex, capture_output=True, text=True, env={**os.environ, "BUILD_DIR": build_dir})
        self.assertEqual(res_dex.returncode, 0, res_dex.stderr)
        self.assertTrue(os.path.isfile(os.path.join(build_dir, "class_files.txt")))
        self.assertTrue(os.path.isfile(os.path.join(dex_dir, "classes.dex")))


if __name__ == "__main__":
    unittest.main()
