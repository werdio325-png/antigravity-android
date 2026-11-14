#!/usr/bin/env python3
"""
test_shims.py - Unit tests for xdg-open shim URL extraction and browser delegates.
"""
import unittest
import os
import subprocess
import tempfile
import shutil

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
MODULE_ROOT = os.path.dirname(TESTS_DIR)

class TestShims(unittest.TestCase):
    def setUp(self):
        self.system_xdg = os.path.join(MODULE_ROOT, "shims", "system", "xdg_open.sh")
        self.browser_xdg = os.path.join(MODULE_ROOT, "shims", "browser", "xdg_open.sh")

    def _run_shim(self, shim_path: str, args: list) -> subprocess.CompletedProcess:
        env = os.environ.copy()
        # Ensure LD_* are not inherited interfering
        env.pop("LD_PRELOAD", None)
        env.pop("LD_LIBRARY_PATH", None)
        return subprocess.run(
            ["sh", shim_path] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env
        )

    def test_01_http_url_extraction(self):
        for shim in (self.system_xdg, self.browser_xdg):
            self.assertTrue(os.path.isfile(shim), f"Missing shim: {shim}")
            res = self._run_shim(shim, ["http://localhost:3000/dashboard"])
            self.assertEqual(res.returncode, 0)
            self.assertIn("AGY_OPEN_URL=http://localhost:3000/dashboard", res.stdout)
            self.assertIn("ANTIGRAVITY_OPEN_URL: http://localhost:3000/dashboard", res.stdout)

    def test_02_https_with_leading_flags(self):
        for shim in (self.system_xdg, self.browser_xdg):
            res = self._run_shim(shim, ["--new-window", "-u", "https://antigravity.google.com/oauth"])
            self.assertEqual(res.returncode, 0)
            self.assertIn("AGY_OPEN_URL=https://antigravity.google.com/oauth", res.stdout)
            self.assertIn("ANTIGRAVITY_OPEN_URL: https://antigravity.google.com/oauth", res.stdout)

    def test_03_fallback_non_dash_argument(self):
        for shim in (self.system_xdg, self.browser_xdg):
            res = self._run_shim(shim, ["-a", "-b", "file:///storage/emulated/0/docs/manual.html"])
            self.assertEqual(res.returncode, 0)
            self.assertIn("AGY_OPEN_URL=file:///storage/emulated/0/docs/manual.html", res.stdout)

    def test_04_no_arguments_clean_exit(self):
        for shim in (self.system_xdg, self.browser_xdg):
            res = self._run_shim(shim, [])
            self.assertEqual(res.returncode, 0)
            self.assertEqual(res.stdout.strip(), "")

    def test_05_flags_only_clean_exit(self):
        for shim in (self.system_xdg, self.browser_xdg):
            res = self._run_shim(shim, ["--help", "-v", "--version"])
            self.assertEqual(res.returncode, 0)
            self.assertEqual(res.stdout.strip(), "")

    def test_06_browser_delegates_to_xdg_open(self):
        # The shims target Android and start with #!/system/bin/sh; executing the
        # delegate by path needs that interpreter, which only exists on-device.
        if not os.access("/system/bin/sh", os.X_OK):
            self.skipTest("needs the Android shell at /system/bin/sh (shim shebang)")
        # Create a temp dir with xdg-open and delegate shims
        tmp = tempfile.mkdtemp()
        try:
            xdg_dest = os.path.join(tmp, "xdg-open")
            shutil.copyfile(self.system_xdg, xdg_dest)
            os.chmod(xdg_dest, 0o755)

            chrome_dest = os.path.join(tmp, "chrome")
            shutil.copyfile(os.path.join(MODULE_ROOT, "shims", "browser", "chrome.sh"), chrome_dest)
            os.chmod(chrome_dest, 0o755)

            res = subprocess.run(
                ["sh", chrome_dest, "--incognito", "https://google.com"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            self.assertEqual(res.returncode, 0)
            self.assertIn("AGY_OPEN_URL=https://google.com", res.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

if __name__ == "__main__":
    unittest.main()
