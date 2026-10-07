"""Dynamic loading of patch group modules."""

import importlib
import importlib.util
import os


def load_module(path):
    """Import a standalone ``.py`` file by filesystem path (importlib)."""
    name = os.path.splitext(os.path.basename(path))[0]
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_group(name):
    """Import a group package's module: ``groups.<name>.module``.

    Requires the patches directory to be importable (the runner guarantees it).
    """
    return importlib.import_module("groups.%s.module" % name)
