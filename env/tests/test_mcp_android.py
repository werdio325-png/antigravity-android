#!/usr/bin/env python3
"""
Unit & stdio protocol integration tests for:
- env/overlay/bin/agy-mcp-android (MCP JSON-RPC 2.0 handshake, tools/list, tools/call
  against a mocked SystemGatewayServer, mocked rish, and real agy-daemon in temp HOME)
- modules/core/bundle/pack_seed.py (recursive packing of seed/skills/**)
"""

import os
import sys
import json
import shutil
import tempfile
import threading
import subprocess
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
REPO_ROOT = os.path.dirname(ENV_DIR)
MCP_SCRIPT = os.path.join(ENV_DIR, "overlay", "bin", "agy-mcp-android")
DAEMON_SCRIPT = os.path.join(ENV_DIR, "overlay", "bin", "agy-daemon")


class _MockGatewayHandler(BaseHTTPRequestHandler):
    recorded = []
    clipboard_value = "initial clip"
    approval_polls = {}
    voice_state = {"active": False, "muted": False, "status_text": ""}

    def _send_json(self, obj, status=200):
        raw = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/gateway/status"):
            self._send_json({"ok": True, "status": "online"})
            return
        self._send_json({"ok": False, "error": "not found"}, status=404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="replace") if length else "{}"
        req = json.loads(body)
        cmd = req.get("command", "")
        payload = req.get("payload") or {}
        self.__class__.recorded.append({"command": cmd, "payload": payload})

        if cmd == "system_info":
            self._send_json({
                "ok": True,
                "battery": 84,
                "charging": True,
                "network": "wifi",
                "storage_free_mb": 24576,
                "memory_free_mb": 1024,
            })
        elif cmd == "notify":
            self._send_json({
                "ok": True,
                "tag": payload.get("tag", "agy_task"),
                "id": payload.get("id", 42),
                "request_id": payload.get("request_id"),
            })
        elif cmd == "approval_poll":
            req_id = payload.get("id", "")
            count = self.__class__.approval_polls.get(req_id, 0) + 1
            self.__class__.approval_polls[req_id] = count
            if count >= 2:
                if req_id == "req_comment_99":
                    self._send_json({
                        "ok": True,
                        "id": req_id,
                        "status": "decided",
                        "decision": "deny_with_comment",
                        "comment": "Wait until CI turns green",
                        "cascade_id": "casc_77",
                        "decided_at": 1700000123456,
                    })
                else:
                    self._send_json({
                        "ok": True,
                        "id": req_id,
                        "status": "decided",
                        "decision": "approve",
                        "comment": None,
                        "decided_at": 1700000123456,
                    })
            else:
                self._send_json({
                    "ok": True,
                    "id": req_id,
                    "status": "pending",
                    "decision": None,
                    "comment": None,
                })
        elif cmd == "subagent_notify":
            self._send_json({
                "ok": True,
                "tag": payload.get("tag", "subagent_1"),
                "id": payload.get("id", 77),
                "channel": "agy_subagents",
                "subagent_id": payload.get("subagent_id"),
                "request_id": payload.get("request_id"),
            })
        elif cmd == "voice_service":
            act = payload.get("action", "status")
            st = self.__class__.voice_state
            if act == "start":
                st["active"] = True
                st["muted"] = False
                st["status_text"] = payload.get("status_text", "Voice active")
            elif act == "stop":
                st["active"] = False
                st["muted"] = False
                st["status_text"] = ""
            elif act == "mute":
                st["muted"] = True
            elif act == "unmute":
                st["muted"] = False
            elif act == "toggle_mute":
                st["muted"] = not st["muted"]
            self._send_json({"ok": True, **st})
        elif cmd == "clipboard":
            if payload.get("action") == "get":
                self._send_json({"ok": True, "text": self.__class__.clipboard_value})
            else:
                self.__class__.clipboard_value = payload.get("text", "")
                self._send_json({"ok": True, "length": len(self.__class__.clipboard_value)})
        elif cmd == "toast":
            self._send_json({"ok": True})
        elif cmd == "vibrate":
            self._send_json({"ok": True})
        elif cmd == "torch":
            self._send_json({"ok": True, "enabled": bool(payload.get("enabled"))})
        elif cmd == "browser_auto":
            action = payload.get("action")
            if action == "navigate":
                self._send_json({"ok": True, "url": payload.get("url"), "title": "Example Page", "status": 200})
            elif action == "screenshot":
                self._send_json({"ok": True, "path": payload.get("path") or "/tmp/browser_auto/1.png", "width": 1080, "height": 1920})
            elif action == "get_text":
                self._send_json({"ok": True, "text": "Hello World", "url": "https://example.com", "title": "Example Page"})
            elif action == "eval":
                self._send_json({"ok": True, "result": "42"})
            elif action == "console_logs":
                self._send_json({"ok": True, "logs": ["[LOG] ready"]})
            elif action == "close":
                self._send_json({"ok": True, "closed": True})
            else:
                self._send_json({"ok": True, "action": action})
        else:
            self._send_json({"ok": False, "error": f"unknown command: {cmd}"})

    def log_message(self, format, *args):
        pass


class TestMcpAndroidServer(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_mcp_test_")
        self.home_dir = os.path.join(self.tmp_dir, "files")
        self.prefix_dir = os.path.join(self.home_dir, "usr")
        os.makedirs(os.path.join(self.home_dir, "logs"), exist_ok=True)
        os.makedirs(os.path.join(self.prefix_dir, "bin"), exist_ok=True)

        _MockGatewayHandler.recorded = []
        _MockGatewayHandler.clipboard_value = "initial clip"
        _MockGatewayHandler.approval_polls = {}
        self.server = HTTPServer(("127.0.0.1", 0), _MockGatewayHandler)
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

        # Write HOME/logs/gateway.port to test automatic port discovery
        with open(os.path.join(self.home_dir, "logs", "gateway.port"), "w", encoding="utf-8") as f:
            f.write(f"{self.port}\n")

        # Create a small mock rish executable (cross-platform .cmd on Windows, sh on POSIX)
        if os.name == "nt":
            self.mock_rish = os.path.join(self.prefix_dir, "bin", "mock_rish.cmd")
            with open(self.mock_rish, "w", encoding="utf-8") as f:
                f.write("@echo off\r\necho MOCK_RISH_OK %*\r\n")
        else:
            self.mock_rish = os.path.join(self.prefix_dir, "bin", "mock_rish.sh")
            with open(self.mock_rish, "w", encoding="utf-8") as f:
                f.write("#!/bin/sh\necho \"MOCK_RISH_OK $*\"\n")
            os.chmod(self.mock_rish, 0o755)

        env = os.environ.copy()
        env["HOME"] = self.home_dir
        env["PREFIX"] = self.prefix_dir
        env["AGY_RISH_BIN"] = self.mock_rish
        env["AGY_DAEMON_BIN"] = DAEMON_SCRIPT
        env.pop("AGY_GATEWAY_PORT", None)

        self.proc = subprocess.Popen(
            [sys.executable, MCP_SCRIPT],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=env,
        )

    def tearDown(self):
        if self.proc.poll() is None:
            try:
                self.proc.stdin.close()
                self.proc.wait(timeout=5)
            except Exception:
                self.proc.kill()
                self.proc.wait(timeout=5)
        for stream in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            try:
                if stream and not stream.closed:
                    stream.close()
            except Exception:
                pass
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _rpc(self, msg):
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        if "id" not in msg or str(msg.get("method", "")).startswith("notifications/"):
            return None
        line = self.proc.stdout.readline()
        if not line:
            self.fail(f"MCP server closed stdout unexpectedly; stderr={self.proc.stderr.read()}")
        return json.loads(line)

    def _call_tool(self, req_id, name, arguments=None):
        resp = self._rpc({
            "jsonrpc": "2.0",
            "id": req_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments or {}},
        })
        self.assertNotIn("error", resp, f"Unexpected JSON-RPC error: {resp}")
        result = resp["result"]
        text = result["content"][0]["text"]
        return json.loads(text), result.get("isError", False)

    def test_handshake_tools_list_and_all_calls(self):
        # 1. initialize
        init_resp = self._rpc({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "test-client", "version": "1.0"},
            },
        })
        self.assertEqual(init_resp["id"], 1)
        self.assertEqual(init_resp["result"]["protocolVersion"], "2024-11-05")
        self.assertEqual(init_resp["result"]["serverInfo"]["name"], "android-device")

        # 2. notifications/initialized (must produce no response)
        self._rpc({"jsonrpc": "2.0", "method": "notifications/initialized"})

        # 3. ping
        ping_resp = self._rpc({"jsonrpc": "2.0", "id": 2, "method": "ping"})
        self.assertEqual(ping_resp["result"], {})

        # 4. tools/list
        list_resp = self._rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/list"})
        tools = list_resp["result"]["tools"]
        names = {t["name"] for t in tools}
        expected_tools = {
            "android_device_info",
            "android_notify",
            "android_wait_approval",
            "android_subagent_notify",
            "android_voice_service",
            "android_clipboard_get",
            "android_clipboard_set",
            "android_toast",
            "android_vibrate",
            "android_torch",
            "android_shizuku_exec",
            "android_browser_automate",
            "android_schedule_task",
        }
        self.assertEqual(names, expected_tools)
        for t in tools:
            self.assertEqual(t["inputSchema"]["type"], "object")
            self.assertIn("properties", t["inputSchema"])

        # 5. android_device_info
        data, is_err = self._call_tool(4, "android_device_info")
        self.assertFalse(is_err)
        self.assertEqual(data["battery"], 84)
        self.assertTrue(data["charging"])

        # 6. android_notify with Approve/Reject actions + android_wait_approval
        notify_data, is_err = self._call_tool(5, "android_notify", {
            "title": "Confirm Deploy",
            "message": "Ship build #12?",
            "channel": "agy_alerts",
            "request_id": "req_deploy_12",
            "actions": [
                {"id": "approve", "label": "Approve"},
                {"id": "reject", "label": "Reject"},
            ],
        })
        self.assertFalse(is_err)
        self.assertEqual(notify_data["request_id"], "req_deploy_12")
        last_gw = _MockGatewayHandler.recorded[-1]
        self.assertEqual(last_gw["command"], "notify")
        self.assertEqual(len(last_gw["payload"]["actions"]), 2)

        wait_data, is_err = self._call_tool(6, "android_wait_approval", {
            "request_id": "req_deploy_12",
            "timeout_sec": 5,
            "poll_interval_ms": 200,
        })
        self.assertFalse(is_err)
        self.assertEqual(wait_data["status"], "decided")
        self.assertEqual(wait_data["decision"], "approve")

        # 6b. android_notify approval preset + Deny w/ Comment inline RemoteInput result
        preset_notify, is_err = self._call_tool(61, "android_notify", {
            "approval": True,
            "title": "Run DB Migration?",
            "message": "Drop legacy table",
            "request_id": "req_comment_99",
            "cascade_id": "casc_77",
            "reply_label": "Why deny?",
        })
        self.assertFalse(is_err)
        self.assertEqual(preset_notify["request_id"], "req_comment_99")
        last_gw = _MockGatewayHandler.recorded[-1]
        self.assertTrue(last_gw["payload"]["approval"])
        self.assertEqual(last_gw["payload"]["cascade_id"], "casc_77")
        self.assertEqual(last_gw["payload"]["reply_label"], "Why deny?")

        comment_wait, is_err = self._call_tool(62, "android_wait_approval", {
            "request_id": "req_comment_99",
            "timeout_sec": 5,
            "poll_interval_ms": 200,
        })
        self.assertFalse(is_err)
        self.assertEqual(comment_wait["decision"], "deny_with_comment")
        self.assertEqual(comment_wait["comment"], "Wait until CI turns green")
        self.assertEqual(comment_wait["cascade_id"], "casc_77")

        # 6c. android_subagent_notify
        sub_res, is_err = self._call_tool(63, "android_subagent_notify", {
            "action": "post",
            "subagent_id": "worker-1",
            "cascade_id": "casc_sub_1",
            "role": "verifier",
            "message": "Running tests 3/5",
            "progress": 60,
            "max": 100,
            "cancellable": True,
        })
        self.assertFalse(is_err)
        self.assertEqual(sub_res["channel"], "agy_subagents")
        self.assertEqual(sub_res["subagent_id"], "worker-1")

        # 6d. android_voice_service (start -> mute -> status -> stop)
        vs_start, is_err = self._call_tool(64, "android_voice_service", {
            "action": "start",
            "status_text": "Listening...",
            "speakerphone": True,
        })
        self.assertFalse(is_err)
        self.assertTrue(vs_start["active"])
        self.assertFalse(vs_start["muted"])

        vs_mute, is_err = self._call_tool(65, "android_voice_service", {"action": "mute"})
        self.assertFalse(is_err)
        self.assertTrue(vs_mute["muted"])

        vs_stop, is_err = self._call_tool(66, "android_voice_service", {"action": "stop"})
        self.assertFalse(is_err)
        self.assertFalse(vs_stop["active"])

        # 7. clipboard set & get
        set_res, is_err = self._call_tool(7, "android_clipboard_set", {"text": "hello mcp"})
        self.assertFalse(is_err)
        self.assertEqual(set_res["length"], 9)
        get_res, is_err = self._call_tool(8, "android_clipboard_get")
        self.assertFalse(is_err)
        self.assertEqual(get_res["text"], "hello mcp")

        # 8. toast, vibrate, torch
        _, is_err = self._call_tool(9, "android_toast", {"message": "Saved", "duration": "short"})
        self.assertFalse(is_err)
        _, is_err = self._call_tool(10, "android_vibrate", {"duration_ms": 150})
        self.assertFalse(is_err)
        torch_res, is_err = self._call_tool(11, "android_torch", {"enabled": True})
        self.assertFalse(is_err)
        self.assertTrue(torch_res["enabled"])

        # 9. android_browser_automate (navigate, get_text, screenshot, eval, close)
        nav_res, is_err = self._call_tool(12, "android_browser_automate", {
            "action": "navigate",
            "url": "https://example.com",
            "width": 1080,
            "height": 1920,
        })
        self.assertFalse(is_err)
        self.assertEqual(nav_res["title"], "Example Page")

        shot_res, is_err = self._call_tool(13, "android_browser_automate", {"action": "screenshot"})
        self.assertFalse(is_err)
        self.assertEqual(shot_res["width"], 1080)

        close_res, is_err = self._call_tool(14, "android_browser_automate", {"action": "close"})
        self.assertFalse(is_err)
        self.assertTrue(close_res["closed"])

        # 10. android_shizuku_exec
        shiz_res, is_err = self._call_tool(15, "android_shizuku_exec", {"command": "dumpsys battery"})
        self.assertFalse(is_err)
        self.assertIn("MOCK_RISH_OK", shiz_res["stdout"])

        # 11. android_schedule_task (add -> list -> pause -> remove)
        add_res, is_err = self._call_tool(16, "android_schedule_task", {
            "action": "add",
            "name": "Check CI",
            "interval": "15m",
            "prompt": "Check CI status & report",
        })
        self.assertFalse(is_err)
        self.assertEqual(add_res["status"], "success")
        task_id = add_res["task"]["id"]

        list_res, is_err = self._call_tool(17, "android_schedule_task", {"action": "list"})
        self.assertFalse(is_err)
        self.assertEqual(len(list_res["tasks"]), 1)
        self.assertEqual(list_res["tasks"][0]["id"], task_id)

        pause_res, is_err = self._call_tool(18, "android_schedule_task", {"action": "pause", "id": task_id})
        self.assertFalse(is_err)
        self.assertEqual(pause_res["status"], "success")

        rm_res, is_err = self._call_tool(19, "android_schedule_task", {"action": "remove", "id": task_id})
        self.assertFalse(is_err)
        self.assertEqual(rm_res["status"], "success")

    def test_error_handling(self):
        # Unknown method
        err_resp = self._rpc({"jsonrpc": "2.0", "id": 100, "method": "nonexistent/method"})
        self.assertEqual(err_resp["error"]["code"], -32601)

        # Unknown tool
        bad_tool = self._rpc({
            "jsonrpc": "2.0",
            "id": 101,
            "method": "tools/call",
            "params": {"name": "no_such_tool", "arguments": {}},
        })
        self.assertEqual(bad_tool["error"]["code"], -32602)

        # Malformed JSON
        self.proc.stdin.write("{not valid json\n")
        self.proc.stdin.flush()
        parse_err = json.loads(self.proc.stdout.readline())
        self.assertEqual(parse_err["error"]["code"], -32700)


class TestPackSeedSkills(unittest.TestCase):
    def test_pack_seed_includes_nested_skills(self):
        bundle_dir = os.path.join(REPO_ROOT, "modules", "core", "bundle")
        if bundle_dir not in sys.path:
            sys.path.insert(0, bundle_dir)
        import pack_seed  # noqa: E402

        src_seed = os.path.join(REPO_ROOT, "modules", "core", "seed")
        tmp_out = tempfile.mkdtemp(prefix="agy_seed_test_")
        try:
            copied = pack_seed.pack_seed(src_seed, tmp_out)
            self.assertIn("AGENTS.md", copied)
            for skill in ("android-device", "android-browser", "android-daemon"):
                rel = f"skills/{skill}/SKILL.md"
                self.assertIn(rel, copied)
                full = os.path.join(tmp_out, "skills", skill, "SKILL.md")
                self.assertTrue(os.path.isfile(full))
                with open(full, "r", encoding="utf-8") as f:
                    text = f.read()
                self.assertTrue(text.startswith("---\n"), f"{rel} missing YAML frontmatter")
                self.assertIn(f"name: {skill}", text)
        finally:
            shutil.rmtree(tmp_out, ignore_errors=True)


class TestEnvStageHygiene(unittest.TestCase):
    def test_sanitize_stage_strips_bytecode_and_normalizes_crlf(self):
        scripts_dir = os.path.join(ENV_DIR, "scripts")
        if scripts_dir not in sys.path:
            sys.path.insert(0, scripts_dir)
        import sanitize_stage  # noqa: E402
        from pathlib import Path

        tmp = tempfile.mkdtemp(prefix="agy_sanitize_test_")
        try:
            root = Path(tmp)
            script_file = root / "bin" / "hello.sh"
            script_file.parent.mkdir(parents=True, exist_ok=True)
            script_file.write_bytes(b"#!/usr/bin/env bash\r\necho hi\r\n")

            bin_file = root / "lib" / "native.so"
            bin_file.parent.mkdir(parents=True, exist_ok=True)
            raw_elf = b"\x7fELF\x02\x01\x01\x00\r\nPAYLOAD"
            bin_file.write_bytes(raw_elf)

            pycache_dir = root / "lib" / "daemon" / "__pycache__"
            pycache_dir.mkdir(parents=True, exist_ok=True)
            (pycache_dir / "executor.cpython-312.pyc").write_bytes(b"\x00pyc")
            stray_pyc = root / "stray.pyc"
            stray_pyc.write_bytes(b"\x00pyc")

            removed, normalized = sanitize_stage.sanitize_tree(root)
            self.assertGreaterEqual(removed, 2)
            self.assertEqual(normalized, 1)
            self.assertEqual(script_file.read_bytes(), b"#!/usr/bin/env bash\necho hi\n")
            self.assertEqual(bin_file.read_bytes(), raw_elf)
            self.assertFalse(pycache_dir.exists())
            self.assertFalse(stray_pyc.exists())
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_env_sources_have_no_crlf_or_pycache(self):
        crlf_files = []
        pycache_hits = []
        for sub in ("pkg", "overlay"):
            base = os.path.join(ENV_DIR, sub)
            for r, dirs, files in os.walk(base):
                if "__pycache__" in dirs:
                    pycache_hits.append(os.path.join(r, "__pycache__"))
                for fn in files:
                    p = os.path.join(r, fn)
                    if fn.endswith((".pyc", ".pyo")):
                        pycache_hits.append(p)
                        continue
                    with open(p, "rb") as f:
                        data = f.read()
                    if b"\x00" not in data[:8192] and b"\r\n" in data:
                        crlf_files.append(p)
        self.assertEqual(pycache_hits, [], f"Bytecode artifacts found in env sources: {pycache_hits}")
        self.assertEqual(crlf_files, [], f"CRLF files found in env sources: {crlf_files}")


if __name__ == "__main__":
    unittest.main()
