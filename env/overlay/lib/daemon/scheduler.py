"""
Scheduler module for Antigravity v2 daemon.
Handles cron parsing, delay/interval duration calculations, DNS resolv.conf
guarding, background conversation turn-completion notifications, and the primary
daemon ticking loop.
"""

import os
import glob
import json
import time
import threading
import urllib.request
from datetime import datetime

from .executor import (
    log,
    notify,
    load_tasks,
    save_tasks,
    execute_action_async,
    HOME,
    PID_FILE,
    STOP_EVENT,
)
from .triggers import (
    check_github_repo,
    check_file_watcher,
    check_battery_condition,
    start_webhook_listener,
)

RESOLV_CONF_PATH = os.path.join(HOME, "etc", "resolv.conf")
GATEWAY_PORT_PATH = os.path.join(HOME, "logs", "gateway.port")
BRAIN_GLOB = os.path.join(HOME, ".gemini", "antigravity-app", "brain", "*", ".system_generated", "logs", "transcript.jsonl")


def _post_gateway_notification(title, message, tag="agy_turn_done"):
    """Post a clickable native Android notification via agy-notify (AgyClickableNotify DEX helper)."""
    notify(title, message, tag=tag)


def _guard_resolv_conf(state):
    """Ensure NetworkConfigWriter never leaves resolv.conf without 1.1.1.1 / 8.8.8.8."""
    try:
        st = os.stat(RESOLV_CONF_PATH)
        if st.st_mtime == state.get("resolv_mtime"):
            return
        with open(RESOLV_CONF_PATH, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
        if "1.1.1.1" not in content or "timeout:1 attempts:1" in content:
            lines = [
                l.strip() for l in content.splitlines()
                if l.strip().startswith("nameserver ") and "1.1.1.1" not in l and "8.8.8.8" not in l
            ]
            new_lines = [
                "options timeout:2 attempts:3 rotate",
                "nameserver 1.1.1.1",
                "nameserver 8.8.8.8",
            ] + lines
            tmp_path = RESOLV_CONF_PATH + ".agy_tmp"
            with open(tmp_path, "w", encoding="utf-8") as f:
                f.write("\n".join(new_lines) + "\n")
            os.replace(tmp_path, RESOLV_CONF_PATH)
            log("Restored resilient DNS fallback (1.1.1.1, 8.8.8.8) in etc/resolv.conf")
        state["resolv_mtime"] = os.stat(RESOLV_CONF_PATH).st_mtime
    except Exception:
        pass


def _read_last_jsonl_record(path):
    """Read the last JSON line of a transcript.jsonl file in O(1) seek time."""
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            if size == 0:
                return None
            read_len = min(size, 65536)
            f.seek(size - read_len)
            chunk = f.read(read_len).decode("utf-8", errors="replace")
            lines = [l.strip() for l in chunk.splitlines() if l.strip()]
            for line in reversed(lines):
                if line.startswith("{") and line.endswith("}"):
                    try:
                        return json.loads(line)
                    except Exception:
                        continue
    except Exception:
        pass
    return None


def _check_conversation_turns(state):
    """Watch active transcript.jsonl files and notify when an agent turn completes."""
    mtimes = state.setdefault("transcript_mtimes", {})
    working = state.setdefault("working_convos", set())
    notified_steps = state.setdefault("notified_steps", {})
    initialized = state.get("transcript_initialized", False)

    for path in glob.glob(BRAIN_GLOB):
        try:
            mtime = os.stat(path).st_mtime
        except OSError:
            continue

        if mtimes.get(path) == mtime:
            continue
        mtimes[path] = mtime

        rec = _read_last_jsonl_record(path)
        if not rec:
            continue

        step_idx = rec.get("step_index", -1)
        step_type = rec.get("type", "")
        status = rec.get("status", "")
        tool_calls = rec.get("tool_calls") or []
        tool_names = [tc.get("name", "") for tc in tool_calls if isinstance(tc, dict)]

        if not initialized:
            notified_steps[path] = step_idx
            if step_type == "USER_INPUT" or (step_type == "PLANNER_RESPONSE" and tool_names and "ask_question" not in tool_names):
                working.add(path)
            continue

        if step_type == "USER_INPUT" or (step_type == "PLANNER_RESPONSE" and tool_names and "ask_question" not in tool_names) or step_type == "GENERIC":
            working.add(path)
        elif step_type == "PLANNER_RESPONSE" and status == "DONE":
            if "ask_question" in tool_names and notified_steps.get(path) != step_idx:
                notified_steps[path] = step_idx
                working.discard(path)
                _post_gateway_notification(
                    "Antigravity: Input Needed",
                    "The agent has a question and is waiting for your response.",
                    tag="agy_turn_done",
                )
            elif not tool_names and (path in working or notified_steps.get(path, -1) < step_idx):
                notified_steps[path] = step_idx
                working.discard(path)
                raw_text = (rec.get("content") or "").strip().replace("\n", " ")
                import re
                raw_text = re.sub(r"\[([^\]]+)\]\([^\)]+\)", r"\1", raw_text)
                raw_text = raw_text.replace("**", "").replace("__", "").replace("`", "")
                raw_text = re.sub(r"\s+", " ", raw_text).strip()
                preview = raw_text[:140] + ("..." if len(raw_text) > 140 else "")
                if not preview:
                    preview = "Agent finished working and replied in chat."
                _post_gateway_notification(
                    "Antigravity: Task Complete",
                    preview,
                    tag="agy_turn_done",
                )

    state["transcript_initialized"] = True


def read_daemon_pid():
    try:
        with open(PID_FILE, "r", encoding="utf-8") as f:
            raw = f.read().strip()
        try:
            value = json.loads(raw)
            return int(value.get("pid", 0)) if isinstance(value, dict) else int(value)
        except (ValueError, TypeError, Exception):
            return 0
    except (OSError, ValueError):
        return 0


def parse_duration(s):
    s = str(s).strip()
    if s.endswith("h"):
        return int(float(s[:-1]) * 3600)
    elif s.endswith("m"):
        return int(float(s[:-1]) * 60)
    elif s.endswith("s"):
        return int(float(s[:-1]))
    return int(s)


def _match_cron_field(val, pattern, min_val, max_val):
    pattern = pattern.strip()
    if pattern == "*":
        return True
    for part in pattern.split(","):
        part = part.strip()
        if not part:
            continue
        if "/" in part:
            subparts = part.split("/", 1)
            step = int(subparts[1])
            if subparts[0] == "*":
                if (val - min_val) % step == 0:
                    return True
            else:
                start = int(subparts[0])
                if val >= start and (val - start) % step == 0:
                    return True
        elif "-" in part:
            start, end = part.split("-", 1)
            if int(start) <= val <= int(end):
                return True
        else:
            if int(part) == val:
                return True
    return False


def matches_cron(cron_expr, dt=None):
    """
    Checks if datetime dt matches the 5-field cron:
    minute (0-59), hour (0-23), day of month (1-31), month (1-12), day of week (0-6, 0=Sun).
    """
    if dt is None:
        dt = datetime.now()
    fields = cron_expr.strip().split()
    if len(fields) != 5:
        return False
    m, h, dom, mon, dow = fields

    if not _match_cron_field(dt.minute, m, 0, 59):
        return False
    if not _match_cron_field(dt.hour, h, 0, 23):
        return False
    if not _match_cron_field(dt.day, dom, 1, 31):
        return False
    if not _match_cron_field(dt.month, mon, 1, 12):
        return False
    cron_dow = (dt.weekday() + 1) % 7
    if not _match_cron_field(cron_dow, dow, 0, 6):
        return False
    return True


def daemon_loop():
    """Main scheduler ticking loop."""
    log("Antigravity background micro-daemon loop started (with DNS guard & turn notifier).")
    webhook_threads = {}
    watcher_state = {}

    while not STOP_EVENT.is_set():
        try:
            _guard_resolv_conf(watcher_state)
            _check_conversation_turns(watcher_state)

            tasks = load_tasks()
            now = time.time()
            now_dt = datetime.now()
            changed = False

            for task in tasks:
                if not task.get("enabled", True):
                    continue

                trigger = task.get("trigger", {})
                ttype = trigger.get("type", "delay")
                should_run = False

                if ttype in ("once", "delay"):
                    run_at = trigger.get("run_at", 0)
                    if now >= run_at and not task.get("executed", False):
                        should_run = True
                        task["executed"] = True
                        task["enabled"] = False
                        changed = True

                elif ttype == "interval":
                    interval = trigger.get("interval_sec", 300)
                    last_run = trigger.get("last_run", 0)
                    if (now - last_run) >= interval:
                        should_run = True
                        trigger["last_run"] = now
                        changed = True

                elif ttype == "cron":
                    cron_expr = trigger.get("cron", "")
                    last_min_ts = trigger.get("last_cron_min", 0)
                    curr_min_ts = int(now // 60)
                    if curr_min_ts > last_min_ts:
                        if matches_cron(cron_expr, now_dt):
                            should_run = True
                            trigger["last_cron_min"] = curr_min_ts
                            changed = True

                elif ttype == "github_pr":
                    interval = trigger.get("interval_sec", 180)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        if check_github_repo(trigger, "pulls"):
                            should_run = True
                        changed = True

                elif ttype == "github_issue":
                    interval = trigger.get("interval_sec", 180)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        if check_github_repo(trigger, "issues"):
                            should_run = True
                        changed = True

                elif ttype == "watch_file":
                    interval = trigger.get("interval_sec", 15)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        trigger["last_check_ts"] = now
                        if check_file_watcher(trigger):
                            should_run = True
                        changed = True

                elif ttype == "battery":
                    interval = trigger.get("interval_sec", 300)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        trigger["last_check_ts"] = now
                        if check_battery_condition(trigger):
                            should_run = True
                            task["enabled"] = False
                        changed = True

                elif ttype == "webhook":
                    port = trigger.get("port", 45199)
                    if port not in webhook_threads:
                        t = threading.Thread(target=start_webhook_listener, args=(port,), daemon=True)
                        t.start()
                        webhook_threads[port] = t

                if should_run:
                    execute_action_async(task)

            if changed:
                save_tasks(tasks)

        except Exception as e:
            log(f"Daemon scheduler error: {e}")

        STOP_EVENT.wait(5)
    try:
        if read_daemon_pid() == os.getpid():
            os.unlink(PID_FILE)
    except OSError:
        pass
    log("Antigravity background micro-daemon stopped.")
