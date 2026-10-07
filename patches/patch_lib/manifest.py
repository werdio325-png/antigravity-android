"""Runtime manifest helpers: core version extraction and stable comparison."""

import json
import re


def extract_core_version(data):
    idx = data.find(b"\xff Go buildinf:")
    if idx != -1:
        m = re.search(rb"go1\.\d[^\x00]*", data[idx:idx + 4096])
        if m:
            return m.group().decode("latin1", "ignore")
    m = re.search(rb"go1\.\d[\w.\-+ :,]*", data[:8 << 20])
    if m:
        return m.group().decode("latin1", "ignore")
    return "unknown"


def _stable(manifest):
    d = dict(manifest)
    d.pop("generated_at", None)
    return json.dumps(d, sort_keys=True, default=str)
