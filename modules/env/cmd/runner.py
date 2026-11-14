#!/usr/bin/env python3
"""
runner.py - Telemetry-rich command execution engine with extended response logging.
"""
import sys
import os
import subprocess
import time
import json
import uuid
from datetime import datetime

CLR_RESET = "\033[0m"
CLR_RED = "\033[31m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_BLUE = "\033[34m"
CLR_CYAN = "\033[36m"
CLR_GRAY = "\033[90m"

def _resolve_log_dir():
    home = os.environ.get("HOME")
    if home and os.path.isdir(home):
        return os.path.join(home, "logs")
    try:
        mod_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        ctx_dir = os.path.join(mod_root, "context")
        if ctx_dir not in sys.path:
            sys.path.insert(0, ctx_dir)
        import path_resolver
        return os.path.join(path_resolver.load_paths().get("app_root"), "logs")
    except Exception:
        pass
    return os.path.expanduser("/data/user/0/dev.continuum.antigravity/files/logs")

LOG_DIR = _resolve_log_dir()
CMD_LOG_FILE = os.path.join(LOG_DIR, "commands.jsonl")


def run(cmd, cwd=None, env=None, timeout=60, source="user", verbose=None):
    """
    Executes a shell command and captures detailed execution metrics and responses.
    """
    if verbose is None:
        verbose = os.environ.get("AGY_VERBOSE") == "1" or os.environ.get("DEV_MODE") == "1"

    cmd_id = f"cmd_{int(time.time())}_{uuid.uuid4().hex[:4]}"
    start_ts = time.time()
    iso_time = datetime.fromtimestamp(start_ts).strftime("%Y-%m-%d %H:%M:%S")

    if verbose:
        print(f"\n{CLR_CYAN}┌── [COMMAND #{cmd_id}] [{iso_time}] [source: {source}]{CLR_RESET}")
        print(f"{CLR_CYAN}│{CLR_RESET} $ {cmd}")
        if cwd:
            print(f"{CLR_CYAN}│{CLR_RESET} cwd: {cwd}")

    stdout_str = ""
    stderr_str = ""
    exit_code = -1
    status = "ERROR"

    run_env = os.environ.copy()
    if env:
        run_env.update(env)

    try:
        proc = subprocess.run(
            cmd,
            shell=True,
            cwd=cwd,
            env=run_env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout
        )
        exit_code = proc.returncode
        stdout_str = proc.stdout or ""
        stderr_str = proc.stderr or ""
        status = "SUCCESS" if exit_code == 0 else "FAILED"
    except subprocess.TimeoutExpired as e:
        status = "TIMEOUT"
        stdout_str = e.stdout or ""
        stderr_str = f"Command timed out after {timeout} seconds"
        exit_code = 124
    except Exception as e:
        status = "ERROR"
        stderr_str = str(e)
        exit_code = 1

    duration_ms = int((time.time() - start_ts) * 1000)

    record = {
        "id": cmd_id,
        "time": int(start_ts * 1000),
        "cmd": cmd,
        "cwd": cwd or os.getcwd(),
        "exit_code": exit_code,
        "duration_ms": duration_ms,
        "status": status,
        "source": source,
        "stdout": stdout_str.strip(),
        "stderr": stderr_str.strip()
    }

    # Append to persistent JSONL log
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(CMD_LOG_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception:
        pass

    if verbose:
        stat_color = CLR_GREEN if status == "SUCCESS" else CLR_RED
        print(f"{CLR_CYAN}├──{CLR_RESET} Status: {stat_color}{status}{CLR_RESET} | Exit: {exit_code} | Duration: {duration_ms}ms")
        if stdout_str.strip():
            lines = stdout_str.strip().split("\n")
            print(f"{CLR_CYAN}├──{CLR_RESET} Output ({len(lines)} lines):")
            for line in lines[:20]:
                print(f"{CLR_CYAN}│{CLR_RESET}   {line}")
            if len(lines) > 20:
                print(f"{CLR_CYAN}│{CLR_RESET}   ... (+{len(lines) - 20} more lines)")
        if stderr_str.strip():
            print(f"{CLR_CYAN}├──{CLR_RESET} {CLR_RED}Stderr:{CLR_RESET}")
            for line in stderr_str.strip().split("\n")[:10]:
                print(f"{CLR_CYAN}│{CLR_RESET}   {CLR_RED}{line}{CLR_RESET}")
        print(f"{CLR_CYAN}└──{CLR_RESET}\n")

    return record


def list_recent(limit=20):
    if not os.path.isfile(CMD_LOG_FILE):
        return []
    records = []
    try:
        with open(CMD_LOG_FILE, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if line:
                    records.append(json.loads(line))
    except Exception:
        pass
    return records[-limit:]


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} run <cmd...> | list [limit]", file=sys.stderr)
        sys.exit(1)

    subcmd = sys.argv[1]
    if subcmd == "list":
        limit = int(sys.argv[2]) if len(sys.argv) > 2 else 20
        recent = list_recent(limit)
        print(f"{CLR_BLUE}=== Recent Command Executions ({len(recent)}) ==={CLR_RESET}")
        print(f"{'TIME':10s} {'STATUS':9s} {'EXIT':4s} {'DUR':6s} {'COMMAND'}")
        print("-" * 65)
        for r in recent:
            ts = r.get("time", 0) / 1000
            t_str = datetime.fromtimestamp(ts).strftime("%H:%M:%S") if ts > 0 else "--:--:--"
            st = r.get("status", "?")
            st_col = CLR_GREEN if st == "SUCCESS" else CLR_RED
            ex = str(r.get("exit_code", "?"))
            dur = f"{r.get('duration_ms', 0)}ms"
            cmd = r.get("cmd", "")
            if len(cmd) > 35:
                cmd = cmd[:32] + "..."
            print(f"{CLR_GRAY}{t_str:10s}{CLR_RESET} {st_col}{st:9s}{CLR_RESET} {ex:4s} {dur:6s} {cmd}")
    else:
        # If 'run' was passed explicitly, skip it; otherwise take all args
        if subcmd == "run":
            cmd_str = " ".join(sys.argv[2:])
        else:
            cmd_str = " ".join(sys.argv[1:])
        rec = run(cmd_str, verbose=True)
        sys.exit(rec["exit_code"])


if __name__ == "__main__":
    main()
