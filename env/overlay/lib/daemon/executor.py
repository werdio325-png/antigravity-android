"""
Task executor module for Antigravity v2 daemon.
Handles process execution (bash/python/rish), notification dispatch, logging,
and workspace UI waking.
"""

import os
import sys
import time
import json
import shlex
import threading
import subprocess
from concurrent.futures import ThreadPoolExecutor

PREFIX = os.environ.get("PREFIX") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
HOME = os.environ.get("HOME") or os.path.dirname(PREFIX)


def application_id_from_prefix():
    files_dir = os.path.dirname(os.path.normpath(PREFIX))
    if os.path.basename(files_dir) != "files":
        return ""
    return os.path.basename(os.path.dirname(files_dir))


AGY_APPLICATION_ID = (os.environ.get("AGY_APPLICATION_ID")
                      or os.environ.get("RISH_APPLICATION_ID")
                      or application_id_from_prefix())
RISH_BIN = os.path.join(PREFIX, "bin", "rish")
BASH_BIN = os.path.join(PREFIX, "bin", "bash")
CONFIG_DIR = os.path.join(HOME, ".gemini", "antigravity-app", "daemon")
TASKS_FILE = os.path.join(CONFIG_DIR, "tasks.json")
LOG_FILE = os.path.join(CONFIG_DIR, "daemon.log")
PID_FILE = os.path.join(CONFIG_DIR, "daemon.pid")
STOP_EVENT = threading.Event()

# Global pool for executing tasks without blocking the main scheduler
TASK_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="agy_worker")
RUNNING_JOBS = set()
RUNNING_LOCK = threading.Lock()


def log(msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    formatted = f"[{ts}] {msg}"
    print(formatted, flush=True)
    try:
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(formatted + "\n")
    except Exception:
        pass


def notify(title, message, tag=None):
    log(f"Notification: {title} - {message}")
    cmd = ["agy-notify", "post", "-t", title, "-m", message]
    if tag:
        cmd.extend(["--tag", tag])
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        log(f"Notification failed: {e}")


def load_tasks():
    if not os.path.isfile(TASKS_FILE):
        return []
    try:
        with open(TASKS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        log(f"Error loading tasks: {e}")
        return []


def save_tasks(tasks):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    tmp_path = TASKS_FILE + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(tasks, f, indent=2, ensure_ascii=False)
    os.replace(tmp_path, TASKS_FILE)


def wake_antigravity_app():
    if not AGY_APPLICATION_ID:
        log("Cannot wake app: application id was not supplied or derived from PREFIX")
        return
    log("Waking Antigravity activity to foreground...")
    command = [
        "am", "start", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-p", AGY_APPLICATION_ID
    ]
    try:
        if os.path.isfile(RISH_BIN):
            subprocess.run(
                [RISH_BIN, "-c", " ".join(shlex.quote(part) for part in command)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15
            )
        else:
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
    except Exception as e:
        log(f"Cannot wake app: {e}")


def _run_task_worker(task):
    task_id = task.get("id", "unknown")
    name = task.get("name", task_id)
    command = task.get("command", "")
    prompt = task.get("prompt", "")
    project = task.get("project")
    convo_id = task.get("conversation_id")
    wake_app = task.get("wake_app", False)

    with RUNNING_LOCK:
        RUNNING_JOBS.add(task_id)

    log(f"Worker started for task [{name}] (ID: {task_id})")

    # If prompt is set but command is not, generate a notification + prompt action
    if not command and prompt:
        command = f"agy-notify post -t 'AI Task Trigger' -m '{prompt[:100]}'"

    notify("Antigravity Daemon", f"Running task: {name}", tag=f"task_{task_id}")

    start_t = time.time()
    success = False
    output = ""
    env = os.environ.copy()
    env["PATH"] = f"/data/user/0/dev.continuum.antigravity/files/usr/bin:{env.get('PATH', '')}"
    env["HOME"] = "/data/user/0/dev.continuum.antigravity/files"
    if convo_id:
        env["AGY_CONVERSATION_ID"] = convo_id
    if project:
        env["AGY_PROJECT_DIR"] = project

    cwd = project if (project and os.path.isdir(project)) else "/data/user/0/dev.continuum.antigravity/files"

    try:
        res = subprocess.run(
            command,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=task.get("timeout_sec", 600),
            env=env,
            cwd=cwd,
            executable=BASH_BIN if os.path.isfile(BASH_BIN) else "/system/bin/sh"
        )
        success = (res.returncode == 0)
        output = res.stdout.strip()
    except subprocess.TimeoutExpired:
        output = "Task timed out."
        log(f"Task [{name}] timed out.")
    except Exception as e:
        output = f"Execution error: {e}"
        log(f"Task [{name}] failed: {e}")

    duration = int(time.time() - start_t)
    status_str = "Completed successfully" if success else "Failed"
    log(f"Task [{name}] {status_str} in {duration}s. Output summary: {output[:200]}")

    notify(
        f"Task {status_str}: {name}",
        f"Finished in {duration}s. {output[:120]}",
        tag=f"task_done_{task_id}"
    )

    if wake_app:
        wake_antigravity_app()

    # Update execution metadata in task record
    try:
        all_tasks = load_tasks()
        for t in all_tasks:
            if t.get("id") == task_id:
                t["last_run_status"] = "success" if success else "failed"
                t["last_run_duration"] = duration
                t["last_run_ts"] = time.time()
                t["last_output"] = output[:1000]
                break
        save_tasks(all_tasks)
    except Exception as e:
        log(f"Error saving task completion status: {e}")

    with RUNNING_LOCK:
        RUNNING_JOBS.discard(task_id)


def execute_action_async(task):
    task_id = task.get("id", "unknown")
    with RUNNING_LOCK:
        if task_id in RUNNING_JOBS:
            log(f"Task [{task_id}] already running. Skipping overlap.")
            return
    TASK_EXECUTOR.submit(_run_task_worker, task)
