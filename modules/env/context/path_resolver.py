"""
path_resolver.py - Resolves environment filesystem paths from spec without hardcoded magic strings.
"""
import os
import json
from typing import Dict, Optional

SPEC_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "spec", "paths.json")

def get_application_id() -> str:
    """
    Returns the dynamic application ID based on environment variables or defaults to 'dev.continuum.antigravity'.
    Priority:
    1. AGY_APPLICATION_ID
    2. APPLICATION_ID
    3. DEV_MODE detection (if DEV_MODE == "1", default to "dev.continuum.antigravity.dev")
    4. Default "dev.continuum.antigravity"
    """
    app_id = os.environ.get("AGY_APPLICATION_ID") or os.environ.get("APPLICATION_ID")
    if app_id:
        return app_id.strip()
    if os.environ.get("DEV_MODE") == "1":
        return "dev.continuum.antigravity.dev"
    return "dev.continuum.antigravity"

def load_paths(custom_spec: Optional[str] = None, app_id: Optional[str] = None) -> Dict[str, str]:
    path = custom_spec or SPEC_PATH
    with open(path, "r", encoding="utf-8") as f:
        paths = json.load(f)

    pkg_name = app_id or get_application_id()
    replacements = {
        "package_name": pkg_name,
        "app_id": pkg_name,
        "APPLICATION_ID": pkg_name
    }

    resolved = {}
    for k, v in paths.items():
        if isinstance(v, str):
            formatted = v.format(**replacements)
            resolved[k] = os.path.abspath(os.path.expanduser(formatted))
        else:
            resolved[k] = v
    return resolved

def resolve_subpath(key: str, rel_path: str, custom_spec: Optional[str] = None, app_id: Optional[str] = None) -> str:
    paths = load_paths(custom_spec=custom_spec, app_id=app_id)
    if key not in paths:
        raise KeyError(f"Unknown path key: '{key}'. Available: {list(paths.keys())}")
    return os.path.join(paths[key], rel_path.lstrip("/"))

