#!/usr/bin/env python3
"""
Unit tests for env/overlay/lib/daemon/executor.py:
- Dynamic PREFIX / HOME resolution (no hardcoded /data/user/0/com.agy/files)
- Shell-injection / quote safety when prompt contains dangerous metacharacters
- Real ConnectRPC AI execution (StartCascade + SendUserCascadeMessage) against a mock server
- Direct SendUserCascadeMessage when conversation_id is already provided
- Safe fallback notification via argv list when core.state.json is missing or server unreachable
- Shell command execution uses dynamic HOME / PREFIX / cwd
"""

import os
import sys
import json
import shutil
import tempfile
import threading
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, HTTPServer

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
OVERLAY_LIB = os.path.join(ENV_DIR, "overlay", "lib")
if OVERLAY_LIB not in sys.path:
    sys.path.insert(0, OVERLAY_LIB)

from daemon import executor  # noqa: E402


class _MockConnectRpcHandler(BaseHTTPRequestHandler):
    recorded_requests = []
    start_cascade_id = "cascade-test-999"
    fail_mode = False

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
        try:
            parsed = json.loads(raw_body) if raw_body else {}
        except json.JSONDecodeError:
            parsed = {"_raw": raw_body}

        self.__class__.recorded_requests.append({
            "path": self.path,
            "csrf": self.headers.get("x-codeium-csrf-token", ""),
            "connect_version": self.headers.get("Connect-Protocol-Version", ""),
            "content_type": self.headers.get("Content-Type", ""),
            "body": parsed,
        })

        if self.__class__.fail_mode:
            resp = json.dumps({"code": "unavailable", "message": "mock core down"}).encode("utf-8")
            self.send_response(503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
            return

        if self.path.endswith("/StartCascade"):
            payload = {"cascadeId": self.__class__.start_cascade_id}
        elif self.path.endswith("/SendUserCascadeMessage"):
            payload = {}
        else:
            self.send_response(404)
            self.end_headers()
            return

        resp = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, format, *args):
        pass


class TestDaemonExecutor(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_exec_test_")
        # Simulate multi-user / dev package layout (NOT /data/user/0/com.agy/files)
        self.home_dir = os.path.join(self.tmp_dir, "user10", "com.agy.dev", "files")
        self.prefix_dir = os.path.join(self.home_dir, "usr")
        os.makedirs(os.path.join(self.prefix_dir, "bin"), exist_ok=True)
        os.makedirs(os.path.join(self.home_dir, "logs"), exist_ok=True)

        self._orig = {
            "PREFIX": executor.PREFIX,
            "HOME": executor.HOME,
            "CONFIG_DIR": executor.CONFIG_DIR,
            "TASKS_FILE": executor.TASKS_FILE,
            "LOG_FILE": executor.LOG_FILE,
            "PID_FILE": executor.PID_FILE,
            "BASH_BIN": executor.BASH_BIN,
            "RISH_BIN": executor.RISH_BIN,
            "AGY_APPLICATION_ID": executor.AGY_APPLICATION_ID,
        }
        executor.PREFIX = self.prefix_dir
        executor.HOME = self.home_dir
        executor.CONFIG_DIR = os.path.join(self.home_dir, ".gemini", "antigravity-app", "daemon")
        executor.TASKS_FILE = os.path.join(executor.CONFIG_DIR, "tasks.json")
        executor.LOG_FILE = os.path.join(executor.CONFIG_DIR, "daemon.log")
        executor.PID_FILE = os.path.join(executor.CONFIG_DIR, "daemon.pid")
        executor.BASH_BIN = os.path.join(self.prefix_dir, "bin", "bash")
        executor.RISH_BIN = os.path.join(self.prefix_dir, "bin", "rish")
        executor.AGY_APPLICATION_ID = executor.application_id_from_prefix(self.prefix_dir)

        _MockConnectRpcHandler.recorded_requests = []
        _MockConnectRpcHandler.fail_mode = False
        self.server = HTTPServer(("127.0.0.1", 0), _MockConnectRpcHandler)
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        for k, v in self._orig.items():
            setattr(executor, k, v)
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _write_core_state(self, ready=True, csrf="test-csrf-secret"):
        state = {
            "ready": ready,
            "https_port": self.port,
            "csrf_token": csrf,
            "base_url": f"http://127.0.0.1:{self.port}",
            "gateway_port": 45160,
            "pid": 12345,
            "ready_at": 1700000000000,
        }
        path = executor.get_core_state_path()
        with open(path, "w", encoding="utf-8") as f:
            json.dump(state, f)
        return path

    def test_no_hardcoded_com_agy_path_in_source(self):
        src_path = os.path.join(OVERLAY_LIB, "daemon", "executor.py")
        with open(src_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertNotIn("/data/user/0/com.agy/files", content)

    def test_dynamic_prefix_home_and_app_id(self):
        self.assertEqual(executor.application_id_from_prefix(self.prefix_dir), "com.agy.dev")
        env = executor._build_task_env(convo_id="conv-1", project=self.tmp_dir)
        self.assertEqual(env["HOME"], self.home_dir)
        self.assertEqual(env["PREFIX"], self.prefix_dir)
        self.assertTrue(env["PATH"].startswith(os.path.join(self.prefix_dir, "bin")))
        self.assertEqual(env["AGY_CONVERSATION_ID"], "conv-1")
        self.assertEqual(env["AGY_PROJECT_DIR"], self.tmp_dir)
        self.assertEqual(executor._resolve_task_cwd(None), self.home_dir)

    def test_connectrpc_ai_prompt_start_and_send(self):
        self._write_core_state(ready=True, csrf="csrf-xyz-123")
        project_dir = os.path.join(self.tmp_dir, "my_project")
        os.makedirs(project_dir, exist_ok=True)
        task = {
            "id": "task_ai_1",
            "name": "Nightly Review",
            "command": "",
            "prompt": "Check 'single' and \"double\" quotes + `backticks`",
            "project": project_dir,
            "conversation_id": "",
        }
        executor.save_tasks([task])

        with mock.patch.object(executor, "notify", return_value=True) as mock_notify:
            executor._run_task_worker(task)

        reqs = _MockConnectRpcHandler.recorded_requests
        self.assertEqual(len(reqs), 2)

        # 1. StartCascade
        self.assertEqual(reqs[0]["path"], "/exa.language_server_pb.LanguageServerService/StartCascade")
        self.assertEqual(reqs[0]["csrf"], "csrf-xyz-123")
        self.assertEqual(reqs[0]["connect_version"], "1")
        self.assertEqual(reqs[0]["body"].get("source"), 1)
        uris = reqs[0]["body"].get("workspaceUris", [])
        self.assertEqual(len(uris), 1)
        self.assertTrue(uris[0].startswith("file://"))

        # 2. SendUserCascadeMessage
        self.assertEqual(reqs[1]["path"], "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage")
        self.assertEqual(reqs[1]["csrf"], "csrf-xyz-123")
        self.assertEqual(reqs[1]["body"].get("cascadeId"), "cascade-test-999")
        self.assertEqual(
            reqs[1]["body"].get("items"),
            [{"text": "Check 'single' and \"double\" quotes + `backticks`"}],
        )

        # Verify persisted task state
        saved = executor.load_tasks()
        self.assertEqual(saved[0]["last_run_status"], "success")
        self.assertEqual(saved[0]["last_cascade_id"], "cascade-test-999")
        self.assertGreaterEqual(mock_notify.call_count, 2)

    def test_connectrpc_reuses_existing_conversation_id(self):
        self._write_core_state(ready=True, csrf="csrf-existing")
        task = {
            "id": "task_ai_2",
            "name": "Follow-up",
            "command": "",
            "prompt": "Continue analysis",
            "conversation_id": "existing-cascade-42",
        }
        executor.save_tasks([task])

        with mock.patch.object(executor, "notify", return_value=True):
            executor._run_task_worker(task)

        reqs = _MockConnectRpcHandler.recorded_requests
        self.assertEqual(len(reqs), 1)
        self.assertEqual(reqs[0]["path"], "/exa.language_server_pb.LanguageServerService/SendUserCascadeMessage")
        self.assertEqual(reqs[0]["body"]["cascadeId"], "existing-cascade-42")
        self.assertEqual(reqs[0]["body"]["items"], [{"text": "Continue analysis"}])

    def test_prompt_quote_injection_safe_fallback_when_core_missing(self):
        # No core.state.json written -> ConnectRPC must fail and fall back to safe argv notify()
        pwn_marker = os.path.join(self.tmp_dir, "INJECTED")
        dangerous_prompt = f"hi'; touch '{pwn_marker}'; echo \"$(touch '{pwn_marker}')\" `touch '{pwn_marker}'`"
        task = {
            "id": "task_inj_1",
            "name": "Quote Safety Test",
            "command": "",
            "prompt": dangerous_prompt,
        }
        executor.save_tasks([task])

        subprocess_calls = []

        def fake_run(cmd, *args, **kwargs):
            subprocess_calls.append((cmd, kwargs))
            return mock.Mock(returncode=0, stdout="", stderr="")

        with mock.patch.object(executor.subprocess, "run", side_effect=fake_run):
            executor._run_task_worker(task)

        # Must never create pwn_marker and must never invoke shell=True for prompt tasks
        self.assertFalse(os.path.exists(pwn_marker))
        self.assertGreaterEqual(len(subprocess_calls), 3)
        for cmd, kwargs in subprocess_calls:
            self.assertIsInstance(cmd, list, "Fallback notification must use safe argv list, not shell string")
            self.assertFalse(kwargs.get("shell", False), "Prompt fallback must never use shell=True")

        # Verify fallback notification included the raw prompt safely in argv
        fallback_calls = [c for c, _ in subprocess_calls if "AI Task Trigger" in c]
        self.assertEqual(len(fallback_calls), 1)
        self.assertIn(f"Quote Safety Test: {dangerous_prompt[:140]}", fallback_calls[0])

        saved = executor.load_tasks()
        self.assertEqual(saved[0]["last_run_status"], "failed")
        self.assertIn("fallback notification", saved[0]["last_output"].lower())

    def test_shell_command_uses_dynamic_home_and_prefix(self):
        out_file = os.path.join(self.tmp_dir, "env_dump.json")
        py_cmd = (
            f'"{sys.executable}" -c '
            f'"import json, os; '
            f'open({out_file!r}, \'w\').write(json.dumps({{\'HOME\': os.environ.get(\'HOME\'), \'PREFIX\': os.environ.get(\'PREFIX\'), \'CWD\': os.getcwd()}}))"'
        )
        task = {
            "id": "task_cmd_1",
            "name": "Env Check",
            "command": py_cmd,
            "prompt": "",
        }
        executor.save_tasks([task])

        with mock.patch.object(executor, "notify", return_value=True):
            executor._run_task_worker(task)

        self.assertTrue(os.path.isfile(out_file))
        with open(out_file, "r", encoding="utf-8") as f:
            dumped = json.load(f)
        self.assertEqual(os.path.normcase(dumped["HOME"]), os.path.normcase(self.home_dir))
        self.assertEqual(os.path.normcase(dumped["PREFIX"]), os.path.normcase(self.prefix_dir))
        self.assertEqual(os.path.normcase(dumped["CWD"]), os.path.normcase(self.home_dir))


if __name__ == "__main__":
    unittest.main()
