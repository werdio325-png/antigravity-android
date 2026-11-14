#!/usr/bin/env python3
"""
test_context.py - Unit tests for path resolution and process environment builder.
"""
import unittest
import os
import sys

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_ROOT = os.path.dirname(TESTS_DIR)
sys.path.insert(0, os.path.join(ENV_ROOT, "context"))

import path_resolver
import env_builder

class TestContext(unittest.TestCase):
    def setUp(self):
        self._orig_env = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._orig_env)

    def test_01_load_paths(self):
        paths = path_resolver.load_paths()
        self.assertIn("app_root", paths)
        self.assertIn("runtime", paths)
        self.assertTrue(paths["app_root"].startswith("/"))

    def test_02_resolve_subpath(self):
        sub = path_resolver.resolve_subpath("runtime", "bin/language_server")
        self.assertTrue(sub.endswith("bin/language_server"))

    def test_03_build_process_env(self):
        env = env_builder.build_process_env(extra_env={"CUSTOM_VAR": "test1234"})
        self.assertEqual(env["LANG"], "C.UTF-8")
        self.assertEqual(env["GODEBUG"], "netdns=go")
        self.assertIn("bin", env["PATH"])
        self.assertEqual(env["CUSTOM_VAR"], "test1234")
        self.assertEqual(env["AGY_APPLICATION_ID"], path_resolver.get_application_id())

    def test_04_default_application_id(self):
        os.environ.pop("AGY_APPLICATION_ID", None)
        os.environ.pop("APPLICATION_ID", None)
        os.environ.pop("DEV_MODE", None)
        self.assertEqual(path_resolver.get_application_id(), "dev.continuum.antigravity")
        paths = path_resolver.load_paths()
        self.assertEqual(paths["app_root"], "/data/user/0/dev.continuum.antigravity/files")

    def test_05_dynamic_application_id_env_var(self):
        os.environ["AGY_APPLICATION_ID"] = "dev.continuum.antigravity.dev"
        paths = path_resolver.load_paths()
        self.assertEqual(paths["app_root"], "/data/user/0/dev.continuum.antigravity.dev/files")
        self.assertEqual(paths["cache"], "/data/user/0/dev.continuum.antigravity.dev/cache")

        env = env_builder.build_process_env()
        self.assertEqual(env["HOME"], "/data/user/0/dev.continuum.antigravity.dev/files")
        self.assertEqual(env["TMPDIR"], "/data/user/0/dev.continuum.antigravity.dev/cache")
        self.assertEqual(env["AGY_APPLICATION_ID"], "dev.continuum.antigravity.dev")

    def test_06_dynamic_application_id_dev_mode(self):
        if "AGY_APPLICATION_ID" in os.environ:
            del os.environ["AGY_APPLICATION_ID"]
        os.environ["DEV_MODE"] = "1"
        paths = path_resolver.load_paths()
        self.assertEqual(paths["app_root"], "/data/user/0/dev.continuum.antigravity.dev/files")

        env = env_builder.build_process_env()
        self.assertEqual(env["HOME"], "/data/user/0/dev.continuum.antigravity.dev/files")
        self.assertEqual(env["AGY_APPLICATION_ID"], "dev.continuum.antigravity.dev")

    def test_07_explicit_app_id_override(self):
        paths = path_resolver.load_paths(app_id="dev.continuum.antigravity.custom")
        self.assertEqual(paths["app_root"], "/data/user/0/dev.continuum.antigravity.custom/files")
        sub = path_resolver.resolve_subpath("runtime", "bin/test", app_id="dev.continuum.antigravity.custom")
        self.assertEqual(sub, "/data/user/0/dev.continuum.antigravity.custom/files/runtime/bin/test")

        env = env_builder.build_process_env(app_id="dev.continuum.antigravity.custom")
        self.assertEqual(env["HOME"], "/data/user/0/dev.continuum.antigravity.custom/files")
        self.assertEqual(env["AGY_APPLICATION_ID"], "dev.continuum.antigravity.custom")

    def test_08_prefix_and_git_config(self):
        env = env_builder.build_process_env(app_id="dev.continuum.antigravity")
        self.assertEqual(env["PREFIX"], "/data/user/0/dev.continuum.antigravity/files/usr")
        self.assertEqual(env["GIT_CONFIG_NOSYSTEM"], "1")

    def test_09_resolve_subpath_leading_slash(self):
        sub1 = path_resolver.resolve_subpath("runtime", "bin/test", app_id="dev.continuum.antigravity")
        sub2 = path_resolver.resolve_subpath("runtime", "/bin/test", app_id="dev.continuum.antigravity")
        self.assertEqual(sub1, sub2)
        self.assertEqual(sub2, "/data/user/0/dev.continuum.antigravity/files/runtime/bin/test")

if __name__ == "__main__":
    unittest.main()

