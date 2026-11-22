#!/usr/bin/env python3
"""
test_elf_validator.py - Unit tests for ELF header, AArch64 architecture, and type verification.
"""
import unittest
import os
import sys

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
MODULE_ROOT = os.path.dirname(TESTS_DIR)
sys.path.insert(0, os.path.join(MODULE_ROOT, "validate"))

import elf_header
import elf_arch
import elf_compat
import checksum_checker

class TestElfValidator(unittest.TestCase):
    def setUp(self):
        self.loader_path = os.path.join(MODULE_ROOT, "glibc", "ld-linux-aarch64.so.1")
        self.archive_path = os.path.join(MODULE_ROOT, "pristine", "language_server.tar.gz")

    def test_01_valid_elf_loader(self):
        self.assertTrue(os.path.isfile(self.loader_path))
        self.assertTrue(elf_header.validate_elf_header(self.loader_path))
        self.assertTrue(elf_arch.validate_aarch64(self.loader_path))
        self.assertIn(elf_compat.validate_executable_type(self.loader_path), ("ET_DYN", "ET_EXEC"))

    def test_02_invalid_elf(self):
        with self.assertRaises(ValueError):
            elf_header.validate_elf_header(self.archive_path)

    def test_03_checksum_verification(self):
        expected_archive_sha = "6847c8e03731e801465a253cbb8c286ffa63c9ee4567dd7a8d5f26ccadaba390"
        self.assertTrue(checksum_checker.verify_checksum(self.archive_path, expected_archive_sha))

if __name__ == "__main__":
    unittest.main()
