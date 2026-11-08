"""
client.py - Lightweight IPC client sending command envelopes to the Android System Gateway.
"""
import json
import os
import urllib.request
import urllib.error
from typing import Dict, Any, Optional

TOKEN_HEADER = "X-Agy-Gateway-Token"


def _gateway_candidates(filename: str):
    candidates = []
    home = os.environ.get("HOME")
    if home:
        candidates.append(os.path.join(home, "logs", filename))

    try:
        from ..context import path_resolver
        app_root = path_resolver.load_paths().get("app_root")
        if app_root:
            candidates.append(os.path.join(app_root, "logs", filename))
    except Exception:
        pass

    candidates.extend([
        f"/data/user/0/com.agy.dev/files/logs/{filename}",
        f"/data/user/0/com.agy/files/logs/{filename}",
    ])
    return candidates


def resolve_gateway_url() -> str:
    if "AGY_GATEWAY_URL" in os.environ:
        return os.environ["AGY_GATEWAY_URL"]

    for pfile in _gateway_candidates("gateway.port"):
        if os.path.isfile(pfile):
            try:
                with open(pfile, "r", encoding="utf-8") as f:
                    p = f.read().strip()
                    if p.isdigit():
                        return f"http://127.0.0.1:{p}/gateway"
            except Exception:
                pass

    is_dev = (
        os.environ.get("DEV_MODE") == "1"
        or os.environ.get("AGY_APPLICATION_ID") == "com.agy.dev"
        or "com.agy.dev" in (os.environ.get("HOME") or "")
    )
    return "http://127.0.0.1:45161/gateway" if is_dev else "http://127.0.0.1:45160/gateway"


def resolve_gateway_token() -> Optional[str]:
    token = os.environ.get("AGY_GATEWAY_TOKEN")
    if token:
        token = token.strip()
        if token:
            return token

    for tfile in _gateway_candidates("gateway.token"):
        if os.path.isfile(tfile):
            try:
                with open(tfile, "r", encoding="utf-8") as f:
                    token = f.read().strip()
                    if token:
                        return token
            except Exception:
                pass
    return None


DEFAULT_GATEWAY_URL = resolve_gateway_url()


def _request(url: str, data: bytes, token: Optional[str], timeout: float = 1.0):
    headers = {"Content-Type": "application/json"}
    if token:
        headers[TOKEN_HEADER] = token
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def send_command(command: str, payload: Dict[str, Any] = None, gateway_url: str = None) -> Dict[str, Any]:
    url = gateway_url or resolve_gateway_url()
    token = resolve_gateway_token()
    packet = {
        "command": command,
        "payload": payload or {}
    }
    data = json.dumps(packet).encode("utf-8")

    try:
        return _request(url, data, token)
    except (urllib.error.URLError, OSError, urllib.error.HTTPError) as e:
        # If default url was used, try the alternate port (45160 <-> 45161)
        if gateway_url is None:
            alt_url = "http://127.0.0.1:45160/gateway" if ":45161/" in url else "http://127.0.0.1:45161/gateway"
            try:
                return _request(alt_url, data, token)
            except Exception:
                pass
        return {"ok": False, "fallback": True, "error": str(e), "packet": packet}
