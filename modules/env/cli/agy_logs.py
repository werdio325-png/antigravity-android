#!/usr/bin/env python3
"""
agy_logs.py - Telemetry and execution log inspector for Antigravity runtime.
"""
import sys
import os
import argparse
import json
import urllib.request
import urllib.error
from datetime import datetime

# Ensure modules/env is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_ENV_DIR = os.path.dirname(SCRIPT_DIR)
if MODULES_ENV_DIR not in sys.path:
    sys.path.insert(0, MODULES_ENV_DIR)

try:
    from bridge.client import resolve_gateway_url, resolve_gateway_token
except ImportError:
    try:
        from modules.env.bridge.client import resolve_gateway_url, resolve_gateway_token
    except ImportError:
        resolve_gateway_url = None
        resolve_gateway_token = None

def _gateway_candidates(filename):
    home = os.environ.get("HOME")
    candidates = []
    if home:
        candidates.append(os.path.join(home, "logs", filename))
    candidates.extend([
        f"/data/user/0/com.agy.dev/files/logs/{filename}",
        f"/data/user/0/com.agy/files/logs/{filename}",
    ])
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

def get_gateway_base():
    if resolve_gateway_url:
        return resolve_gateway_url().rstrip("/")
    if "AGY_GATEWAY_URL" in os.environ:
        return os.environ["AGY_GATEWAY_URL"].rstrip("/")
    for pfile in _gateway_candidates("gateway.port"):
        if os.path.isfile(pfile):
            try:
                with open(pfile, "r", encoding="utf-8") as f:
                    p = f.read().strip()
                    if p.isdigit():
                        return f"http://127.0.0.1:{p}/gateway"
            except Exception:
                pass
    is_dev = os.environ.get("DEV_MODE") == "1" or os.environ.get("AGY_APPLICATION_ID") == "com.agy.dev"
    return "http://127.0.0.1:45161/gateway" if is_dev else "http://127.0.0.1:45160/gateway"

def get_gateway_token():
    token = os.environ.get("AGY_GATEWAY_TOKEN")
    if token and token.strip():
        return token.strip()
    if resolve_gateway_token:
        try:
            token = resolve_gateway_token()
            if token:
                return token
        except Exception:
            pass
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

GATEWAY_BASE = get_gateway_base()
GATEWAY_STATUS_URL = f"{GATEWAY_BASE}/status"
GATEWAY_LOGS_URL = f"{GATEWAY_BASE}/logs"
GATEWAY_COMMANDS_URL = f"{GATEWAY_BASE}/commands"
GATEWAY_CRASH_URL = f"{GATEWAY_BASE}/crash"

# ANSI Colors
CLR_RESET = "\033[0m"
CLR_RED = "\033[31m"
CLR_GREEN = "\033[32m"
CLR_YELLOW = "\033[33m"
CLR_BLUE = "\033[34m"
CLR_CYAN = "\033[36m"
CLR_GRAY = "\033[90m"


def fetch_from_gateway(url):
    try:
        headers = {"User-Agent": "agy-logs"}
        token = get_gateway_token()
        if token:
            headers["X-Agy-Gateway-Token"] = token
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=1.0) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None


def _find_commands_log():
    candidates = []
    home = os.environ.get("HOME")
    if home:
        candidates.append(os.path.join(home, "logs", "commands.jsonl"))
    try:
        from context import path_resolver
        app_root = path_resolver.load_paths().get("app_root")
        if app_root:
            candidates.append(os.path.join(app_root, "logs", "commands.jsonl"))
    except Exception:
        pass
    candidates.extend([
        "/data/user/0/com.agy.dev/files/logs/commands.jsonl",
        "/data/user/0/com.agy/files/logs/commands.jsonl"
    ])
    for p in candidates:
        if os.path.isfile(p):
            return p
    return candidates[0] if candidates else "/data/user/0/com.agy/files/logs/commands.jsonl"


def read_file_fallback(max_lines=50):
    candidates = []
    home = os.environ.get("HOME")
    if home:
        candidates.append(os.path.join(home, "logs", "core.log"))
        candidates.append(os.path.join(home, ".gemini", "logs", "core.log"))
    try:
        from context import path_resolver
        app_root = path_resolver.load_paths().get("app_root")
        if app_root:
            candidates.append(os.path.join(app_root, "logs", "core.log"))
    except Exception:
        pass
    candidates.extend([
        os.path.expanduser("~/.gemini/logs/core.log"),
        "/data/user/0/com.agy.dev/files/logs/core.log",
        "/data/user/0/com.agy/files/logs/core.log",
        "/data/user/0/com.agy/files/standalone_runtime/core_output.log"
    ])
    for path in candidates:
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
                tail = lines[-max_lines:]
                entries = []
                for l in tail:
                    entries.append({"time": 0, "channel": "file", "level": "INFO", "msg": l.rstrip()})
                return entries
    return []


def format_log_entry(entry):
    ts = entry.get("time", 0)
    time_str = datetime.fromtimestamp(ts / 1000).strftime("%H:%M:%S") if ts > 0 else "--:--:--"
    level = entry.get("level", "INFO")
    channel = entry.get("channel", "core").upper()
    msg = entry.get("msg", "")

    color = CLR_RESET
    if level == "ERROR":
        color = CLR_RED
    elif level == "WARN":
        color = CLR_YELLOW
    elif level == "DEBUG":
        color = CLR_GRAY

    return f"{CLR_GRAY}[{time_str}]{CLR_RESET} {color}[{level:5s}]{CLR_RESET} {CLR_BLUE}[{channel}]{CLR_RESET} {msg}"


def _yesno(v):
    return "yes" if v else "no"


def show_status(as_json=False):
    data = fetch_from_gateway(GATEWAY_STATUS_URL)
    if as_json:
        print(json.dumps(data or {"ok": False, "error": "unavailable"}, indent=2))
        return
    if not data:
        print("[INFO] Gateway status unavailable. Is the app/service running?")
        return
    if not data.get("ok"):
        print(f"[ERROR] Status error: {data.get('error', 'unknown')}")
        return

    core = data.get("core") or {}
    env = data.get("env") or {}
    mem = data.get("memory") or {}
    ready = core.get("ready")
    state = "READY" if ready else ("RESTARTING" if core.get("restarting") else "STARTING/NOT READY")
    state_clr = CLR_GREEN if ready else CLR_YELLOW
    print(f"\n{CLR_BLUE}=== Antigravity Status ==={CLR_RESET}")
    if data.get("app_version"):
        print(f"  App version:   {data.get('app_version')}")
    if data.get("core_version"):
        print(f"  Core version:  {data.get('core_version')}")
    if data.get("gateway_port"):
        print(f"  Gateway port:  {data.get('gateway_port')}")
    print(f"  Core state:    {state_clr}{state}{CLR_RESET}")
    if core.get("last_error"):
        print(f"  Last error:    {CLR_RED}{core.get('last_error')}{CLR_RESET}")
    print(f"  Env tools:     usr bash={_yesno(env.get('usr_bash'))}, runtime={_yesno(env.get('runtime_dir'))}, web={_yesno(env.get('web_dir'))}")
    if env.get("storage_free_mb") is not None:
        print(f"  Storage free:  {env.get('storage_free_mb')} MB")
    if mem.get("heap_used_mb") is not None:
        print(f"  App memory:    {mem.get('heap_used_mb')} / {mem.get('heap_max_mb')} MB (heap)")
    print()


def show_crash(as_json=False):
    data = fetch_from_gateway(GATEWAY_CRASH_URL)
    crash = data.get("crash") if data else None

    if not crash or crash == "null":
        # Search crashes directory
        crashes_dirs = []
        home = os.environ.get("HOME")
        if home:
            crashes_dirs.append(os.path.join(home, "logs", "crashes"))
        try:
            from context import path_resolver
            app_root = path_resolver.load_paths().get("app_root")
            if app_root:
                crashes_dirs.append(os.path.join(app_root, "logs", "crashes"))
        except Exception:
            pass
        crashes_dirs.extend([
            "/data/user/0/com.agy.dev/files/logs/crashes",
            "/data/user/0/com.agy/files/logs/crashes"
        ])
        for crashes_dir in crashes_dirs:
            if os.path.isdir(crashes_dir):
                files = sorted(os.listdir(crashes_dir), reverse=True)
                if files:
                    try:
                        with open(os.path.join(crashes_dir, files[0]), "r") as f:
                            crash = json.load(f)
                        break
                    except Exception:
                        pass

    if not crash:
        print("[OK] No crash reports recorded. Runtime is healthy!")
        return

    if as_json:
        print(json.dumps(crash, indent=2))
        return

    ts = crash.get("timestamp", 0)
    time_str = datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M:%S") if ts > 0 else "unknown"
    print(f"\n{CLR_RED}=== CRASH REPORT ({time_str}) ==={CLR_RESET}")
    print(f"  Exit Code:    {crash.get('exit_code', '?')}")
    print(f"  Uptime:       {crash.get('uptime_ms', 0) / 1000.0:.1f}s")
    print(f"  Error:        {crash.get('error', 'none')}")

    mem = crash.get("memory", {})
    if mem:
        print(f"  Memory:       Free={mem.get('free_mb')}MB / Total={mem.get('total_mb')}MB / Max={mem.get('max_mb')}MB")

    log_tail = crash.get("log_tail", [])
    if log_tail:
        print(f"\n{CLR_YELLOW}--- Last Log Output Leading to Crash ---{CLR_RESET}")
        for l in log_tail:
            print(f"  {l}")
    print(f"{CLR_RED}====================================={CLR_RESET}\n")


def show_commands(limit=20, as_json=False):
    # Try gateway first
    data = fetch_from_gateway(f"{GATEWAY_COMMANDS_URL}?limit={limit}")
    commands = data.get("commands") if data else None

    if not commands:
        # Fallback to local commands.jsonl
        log_file = _find_commands_log()
        if os.path.isfile(log_file):
            commands = []
            try:
                with open(log_file, "r", encoding="utf-8", errors="replace") as f:
                    for line in f:
                        line = line.strip()
                        if line:
                            commands.append(json.loads(line))
                commands = commands[-limit:]
            except Exception:
                pass

    if as_json:
        print(json.dumps(commands or [], indent=2))
        return

    if not commands:
        print("[INFO] No command execution history recorded.")
        return

    print(f"\n{CLR_BLUE}=== Command Execution Audit & Responses ({len(commands)}) ==={CLR_RESET}")
    print(f"{'TIME':8s} {'STATUS':9s} {'EXIT':4s} {'DUR':6s} {'ID':15s} {'COMMAND'}")
    print("-" * 75)
    for c in commands:
        ts = c.get("time", 0) / 1000
        t_str = datetime.fromtimestamp(ts).strftime("%H:%M:%S") if ts > 0 else "--:--:--"
        st = c.get("status", "?")
        st_col = CLR_GREEN if st == "SUCCESS" else CLR_RED
        ex = str(c.get("exit_code", "?"))
        dur = f"{c.get('duration_ms', 0)}ms"
        cid = c.get("id", "")
        cmd = c.get("cmd", "")
        if len(cmd) > 30:
            cmd = cmd[:27] + "..."
        print(f"{CLR_GRAY}{t_str:8s}{CLR_RESET} {st_col}{st:9s}{CLR_RESET} {ex:4s} {dur:6s} {CLR_CYAN}{cid:15s}{CLR_RESET} {cmd}")
    print(f"{CLR_GRAY}Tip: Use 'agy-logs --cmd-id <ID>' to view complete output/stderr.{CLR_RESET}\n")


def show_command_detail(cmd_id, as_json=False):
    log_file = _find_commands_log()
    record = None
    if os.path.isfile(log_file):
        with open(log_file, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if cmd_id in line:
                    try:
                        obj = json.loads(line.strip())
                        if obj.get("id") == cmd_id:
                            record = obj
                            break
                    except Exception:
                        pass

    if not record:
        print(f"[ERROR] Command with ID '{cmd_id}' not found.", file=sys.stderr)
        return

    if as_json:
        print(json.dumps(record, indent=2))
        return

    ts = record.get("time", 0) / 1000
    t_str = datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S") if ts > 0 else "unknown"
    st = record.get("status", "?")
    st_col = CLR_GREEN if st == "SUCCESS" else CLR_RED

    print(f"\n{CLR_CYAN}=== COMMAND DETAILS [{record.get('id')}] ==={CLR_RESET}")
    print(f"  Time:        {t_str}")
    print(f"  Status:      {st_col}{st}{CLR_RESET} (Exit code: {record.get('exit_code')})")
    print(f"  Duration:    {record.get('duration_ms')}ms")
    print(f"  Source:      {record.get('source')}")
    print(f"  Directory:   {record.get('cwd')}")
    print(f"  Command:     {CLR_YELLOW}${CLR_RESET} {record.get('cmd')}")

    stdout_str = record.get("stdout", "").strip()
    if stdout_str:
        print(f"\n{CLR_GREEN}--- STDOUT ({len(stdout_str.splitlines())} lines) ---{CLR_RESET}")
        print(stdout_str)

    stderr_str = record.get("stderr", "").strip()
    if stderr_str:
        print(f"\n{CLR_RED}--- STDERR ({len(stderr_str.splitlines())} lines) ---{CLR_RESET}")
        print(stderr_str)
    print(f"{CLR_CYAN}=========================================={CLR_RESET}\n")


def main():
    parser = argparse.ArgumentParser(description="Antigravity Execution Log Inspector")
    parser.add_argument("--tail", "-n", type=int, default=30, help="Number of recent log entries to show")
    parser.add_argument("--channel", help="Filter by channel (e.g. core, supervisor, gateway)")
    parser.add_argument("--level", choices=["DEBUG", "INFO", "WARN", "ERROR"], help="Filter by severity level")
    parser.add_argument("--commands", "-c", action="store_true", help="Display recent command executions and responses")
    parser.add_argument("--cmd-id", help="Display full details and responses for a specific command ID")
    parser.add_argument("--crash", action="store_true", help="Display details of the most recent crash")
    parser.add_argument("--status", action="store_true", help="Display gateway/core health status")
    parser.add_argument("--json", action="store_true", help="Output in raw JSON format")

    args = parser.parse_args()

    if args.status:
        show_status(args.json)
        return

    if args.commands:
        show_commands(args.tail, args.json)
        return

    if args.cmd_id:
        show_command_detail(args.cmd_id, args.json)
        return

    if args.crash:
        show_crash(args.json)
        return

    # Try gateway first
    data = fetch_from_gateway(GATEWAY_LOGS_URL)
    entries = data.get("logs") if data else None

    if entries is None:
        entries = read_file_fallback(args.tail)

    if args.channel:
        entries = [e for e in entries if e.get("channel", "").lower() == args.channel.lower()]
    if args.level:
        entries = [e for e in entries if e.get("level") == args.level]

    entries = entries[-args.tail:]

    if args.json:
        print(json.dumps(entries, indent=2))
    else:
        if not entries:
            print("[INFO] No log entries available.")
        for e in entries:
            print(format_log_entry(e))


if __name__ == "__main__":
    main()

