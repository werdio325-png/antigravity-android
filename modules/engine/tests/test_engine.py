#!/usr/bin/env python3
"""
test_engine.py - Unit tests for the engine compatibility layer.

The catalogue tests use a synthetic "engine" blob that mimics Go's packed string
table, so they run in milliseconds and do not need the 180 MB core binary. A
real-engine test runs only when CONTINUUM_ENGINE_E2E=1.
"""
import json
import os
import struct
import sys
import tempfile
import unittest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENGINE_DIR = os.path.dirname(TESTS_DIR)
sys.path.insert(0, ENGINE_DIR)

import catalog  # noqa: E402
import engine_probe  # noqa: E402


def fake_elf(machine: int = 183, e_type: int = 3) -> bytes:
    ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\x00" * 8
    rest = struct.pack("<HHI", e_type, machine, 1) + b"\x00" * 40
    return ident + rest


def synthetic_engine() -> bytes:
    # Packed like Go rodata: no separators between neighbouring strings.
    blob = (
        b"\x00\x00serving\x00gemini-9.1-halcyon-lowgemini-9.1-halcyon-high"
        b"Gemini 9.1 Halcyon (Low)Gemini 9.1 Halcyon (High)Gemini 9.1 Halcyon"
        b"gemini-9.1-halcyon-mediumGemini 9.1 Halcyon (Medium)"
        b"\x00level-0Level 0\x00di-rings#ring-0\x00"
        b"gemini-for-google-9.1-flashGemini for Google 9.1 Flash\x00"
        b"gemini-9.2-cometGemini 9.2 Comet\x00"
    )
    return fake_elf() + blob


class TestCatalog(unittest.TestCase):

    def setUp(self):
        self.models = catalog.extract_models(synthetic_engine())
        self.by_id = {m["id"]: m for m in self.models}

    def test_discovers_tiered_models(self):
        self.assertIn("gemini-9.1-halcyon", self.by_id)
        entry = self.by_id["gemini-9.1-halcyon"]
        self.assertEqual(entry["display_name"], "Gemini 9.1 Halcyon")
        self.assertEqual(entry["tiers"], ["low", "medium", "high"])
        for tier in ("low", "medium", "high"):
            self.assertIn("gemini-9.1-halcyon-" + tier, self.by_id)

    def test_discovers_untiered_model(self):
        self.assertIn("gemini-9.2-comet", self.by_id)

    def test_rejects_noise(self):
        ids = set(self.by_id)
        self.assertNotIn("level-0", ids)
        self.assertNotIn("ring-0", ids)
        # A fragment of "gemini-for-google-9.1-flash" is not a model of its own.
        self.assertNotIn("google-9.1-flash", ids)

    def test_find_model_by_label_and_id(self):
        self.assertEqual(catalog.find_model(self.models, "Gemini 9.1 Halcyon (High)")["id"],
                         "gemini-9.1-halcyon-high")
        self.assertEqual(catalog.find_model(self.models, "gemini-9.2-comet")["id"],
                         "gemini-9.2-comet")
        self.assertIsNone(catalog.find_model(self.models, "Gemini 4 Hydrogen"))

    def test_simulated_new_engine_adds_models(self):
        # A newer engine build must be picked up without changing app code.
        blob = synthetic_engine() + b"gemini-9.3-pulsarGemini 9.3 Pulsar\x00"
        ids = {m["id"] for m in catalog.extract_models(blob)}
        self.assertIn("gemini-9.3-pulsar", ids)


class TestProbe(unittest.TestCase):

    def test_elf_info_detects_aarch64_pie(self):
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as handle:
            handle.write(fake_elf())
            path = handle.name
        try:
            info = engine_probe.elf_info(path)
            self.assertTrue(info["valid"])
            self.assertTrue(info["aarch64"])
            self.assertTrue(info["pie"])
            self.assertEqual(info["machine"], "aarch64")
        finally:
            os.unlink(path)

    def test_elf_info_rejects_foreign_machine(self):
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as handle:
            handle.write(fake_elf(machine=62))
            path = handle.name
        try:
            info = engine_probe.elf_info(path)
            self.assertFalse(info["aarch64"])
        finally:
            os.unlink(path)


@unittest.skipUnless(os.environ.get("CONTINUUM_ENGINE_E2E") == "1",
                     "set CONTINUUM_ENGINE_E2E=1 to scan the embedded 180 MB engine")
class TestRealEngine(unittest.TestCase):

    def test_embedded_engine_is_aarch64_and_patched(self):
        info = engine_probe.probe()
        self.assertEqual(info["engine"]["version"], "2.11.0")
        self.assertTrue(info["engine"]["elf"]["aarch64"])
        self.assertTrue(info["patches"]["compatible"], info["patches"]["failing_groups"])
        ids = {m["id"] for m in info["catalog"]["models"]}
        self.assertIn("gemini-4-argon", ids)
        # The embedded engine predates these models; the catalogue must not invent them.
        self.assertNotIn("gemini-4-hydrogen", ids)
        self.assertNotIn("gemini-4-carbon", ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
