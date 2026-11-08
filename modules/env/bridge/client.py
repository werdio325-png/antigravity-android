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

    # Best-effort discovery for work profiles / secondary Android users.
    try:
        import glob
        for pattern in (
            f"/data/user/*/com.agy/files/logs/{filename}",
            f"/data/user/*/com.agy.dev/files/logs/{filename}",
        ):
            for path in glob.glob(pattern):
                if path not in candidates:
                    candidates.append(path)
    except Exception:
        pass
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


def exec_start(cmd: str, gateway_url: str = None, **payload) -> Dict[str, Any]:
    """Start a command asynchronously; returns an id for polling/cancel."""
    p = {"cmd": cmd}
    p.update(payload)
    return send_command("exec_start", p, gateway_url=gateway_url)


def exec_status(cmd_id: str, gateway_url: str = None, stdout_offset: int = 0, stderr_offset: int = 0,
                include_output: bool = True) -> Dict[str, Any]:
    """Poll status/output for an async command."""
    return send_command("exec_status", {
        "id": cmd_id,
        "stdout_offset": stdout_offset,
        "stderr_offset": stderr_offset,
        "include_output": include_output,
    }, gateway_url=gateway_url)


def exec_cancel(cmd_id: str, gateway_url: str = None) -> Dict[str, Any]:
    """Request cancellation of an async/running command."""
    return send_command("exec_cancel", {"id": cmd_id}, gateway_url=gateway_url)


def get_status(gateway_url: str = None) -> Dict[str, Any]:
    """Fetch gateway/core health status."""
    return send_command("status", {}, gateway_url=gateway_url)


def doctor(gateway_url: str = None) -> Dict[str, Any]:
    return send_command("doctor", {}, gateway_url=gateway_url)


def repair(gateway_url: str = None, **payload) -> Dict[str, Any]:
    return send_command("repair", payload or {}, gateway_url=gateway_url)


def exec_command(cmd: str, gateway_url: str = None, async_mode: bool = False, poll_interval: float = 0.25,
                 timeout_s: Optional[float] = None, **payload) -> Dict[str, Any]:
    """Run a command. In async_mode, starts then polls until terminal or timeout_s."""
    p = {"cmd": cmd}
    p.update(payload)
    if not async_mode:
        return send_command("exec", p, gateway_url=gateway_url)
    start = exec_start(cmd, gateway_url=gateway_url, **payload)
    cmd_id = start.get("id")
    if not start.get("ok") or not cmd_id:
        return start
    import time
    deadline = (time.time() + timeout_s) if timeout_s else None
    out_off = 0
    err_off = 0
    last = start
    while True:
        if deadline and time.time() > deadline:
            exec_cancel(cmd_id, gateway_url=gateway_url)
            last["timeout"] = True
            return last
        time.sleep(poll_interval)
        last = exec_status(cmd_id, gateway_url=gateway_url, stdout_offset=out_off, stderr_offset=err_off)
        if not last.get("ok"):
            return last
        # keep full accumulated output for caller convenience
        out_off = 0
        err_off = 0
        if not last.get("running", False):
            return last
