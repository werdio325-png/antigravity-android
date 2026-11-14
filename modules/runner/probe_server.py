#!/usr/bin/env python3
"""
probe_server.py - Probes the standalone core HTTP server until active or timeout,
with fail-fast process liveness checks.
"""
import os
import sys
import time
import urllib.request
import urllib.error
from typing import Optional, Union

def resolve_target_pid(pid_arg: Optional[Union[str, int]] = None) -> Optional[int]:
    """Resolves target core PID from CLI arg, environment, or standalone.pid file."""
    if pid_arg is not None:
        if isinstance(pid_arg, int):
            return pid_arg
        s = str(pid_arg).strip()
        if s.isdigit():
            return int(s)
        if os.path.isfile(s):
            try:
                with open(s, "r") as f:
                    content = f.read().strip().split()[0]
                    if content.isdigit():
                        return int(content)
            except Exception:
                pass

    # Check env vars
    for env_var in ("STANDALONE_PID", "AGY_PID"):
        val = os.environ.get(env_var, "").strip()
        if val.isdigit():
            return int(val)

    # Check known PID file locations
    candidates = []
    rt = os.environ.get("RT")
    if rt:
        candidates.append(os.path.join(rt, "standalone.pid"))

    home = os.environ.get("HOME")
    if home:
        candidates.append(os.path.join(home, "standalone_runtime", "standalone.pid"))

    candidates.extend([
        "/data/user/0/dev.continuum.antigravity.dev/files/standalone_runtime/standalone.pid",
        "/data/user/0/dev.continuum.antigravity/files/standalone_runtime/standalone.pid",
        "standalone.pid",
    ])

    for path in candidates:
        if os.path.isfile(path):
            try:
                with open(path, "r") as f:
                    content = f.read().strip().split()[0]
                    if content.isdigit():
                        return int(content)
            except Exception:
                continue

    return None

def is_process_alive(pid: int) -> bool:
    """Checks whether the process with given PID is still running."""
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False

def probe(port: int = 45159, timeout_sec: int = 20, pid: Optional[Union[str, int]] = None) -> bool:
    url = f"http://127.0.0.1:{port}"
    target_pid = resolve_target_pid(pid)
    pid_msg = f" (monitoring PID: {target_pid})" if target_pid else ""
    print(f"[*] Probing core server at {url} (timeout: {timeout_sec}s){pid_msg}...")
    start = time.time()

    while time.time() - start < timeout_sec:
        # Fail-fast: if target PID died, fail immediately
        if target_pid is not None and not is_process_alive(target_pid):
            elapsed = time.time() - start
            print(
                f"[FAIL] Target process (PID {target_pid}) died unexpectedly! Fail-fast aborting probe after {elapsed:.2f}s.",
                file=sys.stderr
            )
            return False

        try:
            req = urllib.request.Request(url, headers={"User-Agent": "AntigravityProbe/1.0"})
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                status = resp.status
                print(f"[OK] Server responded with HTTP status {status} in {time.time() - start:.2f}s!")
                return True
        except urllib.error.HTTPError as e:
            # An HTTP error (e.g. 400 Bad Request or 401 Unauthorized) means the server is UP and responding!
            print(f"[OK] Server responded with HTTP {e.code} in {time.time() - start:.2f}s (Server is UP!)")
            return True
        except Exception:
            time.sleep(0.2)

    print(f"[FAIL] Server at {url} did not respond within {timeout_sec}s.", file=sys.stderr)
    return False

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 45159
    timeout = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    pid = sys.argv[3] if len(sys.argv) > 3 else None
    ok = probe(port, timeout, pid)
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
