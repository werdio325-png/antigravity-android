"""
Task executor module for Antigravity v2 daemon.
Handles process execution (bash/python/rish), real ConnectRPC AI prompt dispatch,
notification dispatch, logging, and workspace UI waking.
"""

import os
import sys
import ssl
import time
import json
import shlex
import pathlib
import threading
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

PREFIX = os.environ.get("PREFIX") or os.path.dirname(os.path.dirname(os.path.dirname(os.path.realpath(__file__))))
HOME = os.environ.get("HOME") or os.path.dirname(PREFIX)


def application_id_from_prefix(prefix=None):
    target_prefix = prefix or PREFIX
    files_dir = os.path.dirname(os.path.normpath(target_prefix))
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

# ConnectRPC service constants matching bundled Antigravity v2.34.0+ language_server
LANGUAGE_SERVER_SERVICE = "exa.language_server_pb.LanguageServerService"
CORTEX_TRAJECTORY_SOURCE_CASCADE_CLIENT = 1
LOOPBACK_HOSTS = frozenset(("127.0.0.1", "localhost", "::1"))

# Global pool for executing tasks without blocking the main scheduler
TASK_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="agy_worker")
RUNNING_JOBS = set()
RUNNING_LOCK = threading.Lock()


def get_core_state_path():
    return os.path.join(HOME, "logs", "core.state.json")


def _notify_bin():
    candidate = os.path.join(PREFIX, "bin", "agy-notify")
    if os.path.isfile(candidate):
        return candidate
    return "agy-notify"


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
    cmd = [_notify_bin(), "post", "-t", str(title), "-m", str(message)]
    if tag:
        cmd.extend(["--tag", str(tag)])
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        return True
    except Exception as e:
        log(f"Notification failed: {e}")
        return False


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
    app_id = AGY_APPLICATION_ID or application_id_from_prefix(PREFIX)
    if not app_id:
        log("Cannot wake app: application id was not supplied or derived from PREFIX")
        return
    log("Waking Antigravity activity to foreground...")
    command = [
        "am", "start", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-p", app_id
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


def load_core_state(state_path=None):
    """Read core.state.json written by CoreProcessSupervisor.

    Returns (base_url, csrf_token, raw_state) or (None, "", None) if unavailable.
    """
    path = state_path or get_core_state_path()
    if not os.path.isfile(path):
        return None, "", None
    try:
        with open(path, "r", encoding="utf-8") as f:
            state = json.load(f)
    except Exception as e:
        log(f"Failed reading core state {path}: {e}")
        return None, "", None

    if not isinstance(state, dict):
        return None, "", None
    if state.get("ready") is False:
        return None, "", state

    base_url = str(state.get("base_url") or "").strip().rstrip("/")
    https_port = state.get("https_port")
    if not base_url and https_port:
        base_url = f"https://127.0.0.1:{int(https_port)}"
    csrf_token = str(state.get("csrf_token") or "")

    if not base_url:
        return None, "", state
    return base_url, csrf_token, state


def _build_ssl_context(url):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme.lower() != "https":
        return None

    host = (parsed.hostname or "").lower()
    cafile = os.environ.get("SSL_CERT_FILE")
    if cafile and not os.path.isfile(cafile):
        cafile = None

    if host in LOOPBACK_HOSTS:
        # Bundled language_server uses an ephemeral self-signed certificate on 127.0.0.1.
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        if cafile:
            try:
                ctx.load_verify_locations(cafile=cafile)
            except Exception:
                pass
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx

    return ssl.create_default_context(cafile=cafile)


def _connect_rpc_call(base_url, method, payload, csrf_token="", timeout=30):
    url = f"{base_url.rstrip('/')}/{LANGUAGE_SERVER_SERVICE}/{method}"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Connect-Protocol-Version": "1",
    }
    if csrf_token:
        headers["x-codeium-csrf-token"] = csrf_token

    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    ssl_ctx = _build_ssl_context(url)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ssl_ctx) as resp:
            raw = resp.read().decode("utf-8", errors="replace").strip()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", errors="replace").strip()[:300]
        except Exception:
            pass
        raise RuntimeError(f"ConnectRPC {method} HTTP {e.code}: {detail or e.reason}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"ConnectRPC {method} connection error: {e.reason}") from e


def _project_to_uri(project):
    if not project:
        return ""
    if project.startswith("file://"):
        return project
    try:
        return pathlib.Path(os.path.abspath(project)).as_uri()
    except Exception:
        return f"file://{project}"


def execute_ai_prompt(prompt, project="", conversation_id="", timeout_sec=60, state_path=None):
    """Dispatch an AI prompt directly to language_server via ConnectRPC.

    Uses StartCascade (when no conversation_id is supplied) followed by
    SendUserCascadeMessage on exa.language_server_pb.LanguageServerService.
    """
    base_url, csrf_token, _ = load_core_state(state_path=state_path)
    if not base_url:
        raise RuntimeError(f"core server unavailable (missing or not ready: {state_path or get_core_state_path()})")

    rpc_timeout = max(5, min(int(timeout_sec or 60), 120))
    cascade_id = str(conversation_id or "").strip()

    if not cascade_id:
        start_payload = {
            "source": CORTEX_TRAJECTORY_SOURCE_CASCADE_CLIENT,
        }
        if project:
            start_payload["workspaceUris"] = [_project_to_uri(project)]
        start_resp = _connect_rpc_call(
            base_url,
            "StartCascade",
            start_payload,
            csrf_token=csrf_token,
            timeout=rpc_timeout,
        )
        cascade_id = str(start_resp.get("cascadeId") or start_resp.get("cascade_id") or "").strip()
        if not cascade_id:
            raise RuntimeError(f"StartCascade did not return cascadeId: {start_resp}")

    send_payload = {
        "cascadeId": cascade_id,
        "items": [{"text": str(prompt)}],
    }
    _connect_rpc_call(
        base_url,
        "SendUserCascadeMessage",
        send_payload,
        csrf_token=csrf_token,
        timeout=rpc_timeout,
    )
    return cascade_id


def _build_task_env(convo_id=None, project=None):
    env = os.environ.copy()
    prefix_bin = os.path.join(PREFIX, "bin")
    existing_path = env.get("PATH", "")
    env["PATH"] = f"{prefix_bin}{os.pathsep}{existing_path}" if existing_path else prefix_bin
    env["PREFIX"] = PREFIX
    env["HOME"] = HOME
    if convo_id:
        env["AGY_CONVERSATION_ID"] = str(convo_id)
    if project:
        env["AGY_PROJECT_DIR"] = str(project)
    return env


def _resolve_task_cwd(project=None):
    if project and os.path.isdir(project):
        return project
    if os.path.isdir(HOME):
        return HOME
    return None


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
    notify("Antigravity Daemon", f"Running task: {name}", tag=f"task_{task_id}")

    start_t = time.time()
    success = False
    output = ""
    dispatched_cascade_id = ""
    env = _build_task_env(convo_id=convo_id, project=project)
    cwd = _resolve_task_cwd(project=project)

    if not command and prompt:
        # Execute real ConnectRPC AI prompt without any shell interpolation.
        try:
            dispatched_cascade_id = execute_ai_prompt(
                prompt=prompt,
                project=project or "",
                conversation_id=convo_id or "",
                timeout_sec=task.get("timeout_sec", 60),
            )
            success = True
            output = f"Dispatched AI prompt to cascade {dispatched_cascade_id}"
            log(f"Task [{name}] {output}")
        except Exception as e:
            reason = str(e)
            log(f"Task [{name}] ConnectRPC AI execution failed ({reason}); posting fallback notification.")
            # Safe argv-list notification (never interpolated through a shell).
            notify(
                "AI Task Trigger",
                f"{name}: {str(prompt)[:140]}",
                tag=f"task_prompt_{task_id}",
            )
            success = False
            output = f"ConnectRPC unavailable ({reason}); posted fallback notification."
    elif command:
        shell_exec = BASH_BIN if os.path.isfile(BASH_BIN) else ("/system/bin/sh" if os.path.isfile("/system/bin/sh") else None)
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
                executable=shell_exec,
            )
            success = (res.returncode == 0)
            output = (res.stdout or "").strip()
        except subprocess.TimeoutExpired:
            output = "Task timed out."
            log(f"Task [{name}] timed out.")
        except Exception as e:
            output = f"Execution error: {e}"
            log(f"Task [{name}] failed: {e}")
    else:
        output = "Task has neither command nor prompt."
        log(f"Task [{name}] skipped: {output}")

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
                if dispatched_cascade_id:
                    t["last_cascade_id"] = dispatched_cascade_id
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
