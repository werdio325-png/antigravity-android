#!/usr/bin/env python3
"""
Unit & stdio protocol integration tests for env/overlay/bin/agy-mcp-browser
(Chrome DevTools-compatible MCP server backed by SystemGatewayServer browser_auto).
"""

import os
import sys
import json
import base64
import shutil
import tempfile
import threading
import subprocess
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
BROWSER_MCP_SCRIPT = os.path.join(ENV_DIR, "overlay", "bin", "agy-mcp-browser")

# Minimal valid 1x1 PNG bytes for screenshot base64 embedding test
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


class _MockBrowserGatewayHandler(BaseHTTPRequestHandler):
    recorded = []
    screenshot_path = ""
    get_text_calls = 0

    def _send_json(self, obj, status=200):
        raw = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="replace") if length else "{}"
        req = json.loads(body)
        cmd = req.get("command", "")
        payload = req.get("payload") or {}
        self.__class__.recorded.append({"command": cmd, "payload": payload})

        if cmd != "browser_auto":
            self._send_json({"ok": False, "error": f"unexpected command: {cmd}"})
            return

        action = payload.get("action")
        if action == "navigate":
            self._send_json({
                "ok": True,
                "url": payload.get("url"),
                "title": "Example Domain",
                "status": 200,
            })
        elif action == "eval":
            script = payload.get("script", "")
            if "data-agy-uid" in script and "Interactive" not in script and "querySelectorAll" in script:
                self._send_json({
                    "ok": True,
                    "result": {
                        "url": "https://example.com/",
                        "title": "Example Domain",
                        "elements": [
                            {"uid": "e1", "role": "link", "tag": "a", "label": "More information...", "href": "https://iana.org"},
                            {"uid": "e2", "role": "textbox", "tag": "input", "label": "Search", "value": ""},
                        ],
                        "text": "Example Domain\nThis domain is for use in illustrative examples.",
                        "truncated": False,
                    },
                })
            elif "KeyboardEvent" in script:
                self._send_json({"ok": True, "result": {"ok": True, "key": "Enter", "tag": "INPUT"}})
            else:
                self._send_json({"ok": True, "result": "Example Domain"})
        elif action == "click":
            self._send_json({"ok": True, "clicked": True, "tag": "A"})
        elif action == "type":
            self._send_json({"ok": True, "typed": True, "length": len(payload.get("text", ""))})
        elif action == "get_text":
            self.__class__.get_text_calls += 1
            text = "Loading..." if self.__class__.get_text_calls < 2 else "Loaded OK — welcome!"
            self._send_json({
                "ok": True,
                "url": "https://example.com/",
                "title": "Example Domain",
                "text": text,
            })
        elif action == "screenshot":
            out = payload.get("path") or self.__class__.screenshot_path
            with open(out, "wb") as f:
                f.write(TINY_PNG)
            self._send_json({
                "ok": True,
                "path": out,
                "width": 1080,
                "height": 1920,
                "bytes": len(TINY_PNG),
            })
        elif action == "console_logs":
            self._send_json({
                "ok": True,
                "logs": [
                    {"level": "log", "message": "boot complete", "line": 1, "source": "app.js"},
                    {"level": "error", "message": "boom", "line": 42, "source": "app.js"},
                ],
            })
        elif action == "close":
            self._send_json({"ok": True, "closed": True})
        else:
            self._send_json({"ok": False, "error": f"unknown action: {action}"})

    def log_message(self, format, *args):
        pass


class TestMcpBrowserServer(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_mcp_browser_test_")
        self.home_dir = os.path.join(self.tmp_dir, "files")
        self.prefix_dir = os.path.join(self.home_dir, "usr")
        os.makedirs(os.path.join(self.home_dir, "logs"), exist_ok=True)
        os.makedirs(os.path.join(self.prefix_dir, "bin"), exist_ok=True)

        _MockBrowserGatewayHandler.recorded = []
        _MockBrowserGatewayHandler.get_text_calls = 0
        _MockBrowserGatewayHandler.screenshot_path = os.path.join(self.tmp_dir, "shot.png")

        self.server = HTTPServer(("127.0.0.1", 0), _MockBrowserGatewayHandler)
        self.port = self.server.server_address[1]
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()

        with open(os.path.join(self.home_dir, "logs", "gateway.port"), "w", encoding="utf-8") as f:
            f.write(f"{self.port}\n")

        env = os.environ.copy()
        env["HOME"] = self.home_dir
        env["PREFIX"] = self.prefix_dir
        env.pop("AGY_GATEWAY_PORT", None)

        self.proc = subprocess.Popen(
            [sys.executable, "-B", BROWSER_MCP_SCRIPT],
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
            self.fail(f"agy-mcp-browser closed stdout unexpectedly; stderr={self.proc.stderr.read()}")
        return json.loads(line)

    def _call_tool(self, req_id, name, arguments=None):
        resp = self._rpc({
            "jsonrpc": "2.0",
            "id": req_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments or {}},
        })
        self.assertNotIn("error", resp, f"Unexpected JSON-RPC error: {resp}")
        return resp["result"]

    def test_chrome_devtools_full_workflow(self):
        # 1. initialize + notifications/initialized + ping
        init = self._rpc({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {"protocolVersion": "2024-11-05"},
        })
        self.assertEqual(init["result"]["serverInfo"]["name"], "chrome-devtools")
        self._rpc({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual(self._rpc({"jsonrpc": "2.0", "id": 2, "method": "ping"})["result"], {})

        # 2. tools/list
        tools = self._rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/list"})["result"]["tools"]
        names = {t["name"] for t in tools}
        expected = {
            "list_pages", "new_page", "select_page", "close_page",
            "navigate_page", "take_snapshot", "take_screenshot",
            "click", "fill", "press_key", "evaluate_script",
            "list_console_messages", "wait_for",
        }
        self.assertEqual(names, expected)

        # 3. list_pages before navigate -> empty
        res = self._call_tool(4, "list_pages")
        self.assertEqual(json.loads(res["content"][0]["text"])["pages"], [])

        # 4. navigate_page
        nav = self._call_tool(5, "navigate_page", {"url": "https://example.com/"})
        self.assertFalse(nav["isError"])
        nav_data = json.loads(nav["content"][0]["text"])
        self.assertEqual(nav_data["pageId"], 1)
        self.assertEqual(nav_data["title"], "Example Domain")

        # 5. take_snapshot
        snap = self._call_tool(6, "take_snapshot")
        self.assertFalse(snap["isError"])
        snap_text = snap["content"][0]["text"]
        self.assertIn("uid=e1", snap_text)
        self.assertIn("More information...", snap_text)
        self.assertIn("uid=e2", snap_text)

        # 6. click(uid) -> maps to [data-agy-uid="e1"]
        clk = self._call_tool(7, "click", {"uid": "e1"})
        self.assertFalse(clk["isError"])
        self.assertEqual(_MockBrowserGatewayHandler.recorded[-1]["payload"]["selector"], '[data-agy-uid="e1"]')

        # 7. fill(uid, value) -> maps to [data-agy-uid="e2"]
        fl = self._call_tool(8, "fill", {"uid": "e2", "value": "antigravity"})
        self.assertFalse(fl["isError"])
        last_payload = _MockBrowserGatewayHandler.recorded[-1]["payload"]
        self.assertEqual(last_payload["action"], "type")
        self.assertEqual(last_payload["selector"], '[data-agy-uid="e2"]')
        self.assertEqual(last_payload["text"], "antigravity")

        # 8. press_key
        pk = self._call_tool(9, "press_key", {"key": "Enter"})
        self.assertFalse(pk["isError"])

        # 9. evaluate_script
        ev = self._call_tool(10, "evaluate_script", {
            "function": "(el) => el.textContent",
            "args": [{"uid": "e1"}],
        })
        self.assertFalse(ev["isError"])
        self.assertIn("data-agy-uid", _MockBrowserGatewayHandler.recorded[-1]["payload"]["script"])

        # 10. take_screenshot (returns text metadata + base64 image/png block)
        shot = self._call_tool(11, "take_screenshot")
        self.assertFalse(shot["isError"])
        self.assertEqual(len(shot["content"]), 2)
        self.assertEqual(shot["content"][1]["type"], "image")
        self.assertEqual(shot["content"][1]["mimeType"], "image/png")
        self.assertEqual(base64.b64decode(shot["content"][1]["data"]), TINY_PNG)

        # 11. list_console_messages (filtered to error)
        logs = self._call_tool(12, "list_console_messages", {"types": ["error"]})
        self.assertFalse(logs["isError"])
        logs_data = json.loads(logs["content"][0]["text"])
        self.assertEqual(logs_data["count"], 1)
        self.assertEqual(logs_data["messages"][0]["message"], "boom")

        # 12. wait_for (matches on 2nd poll)
        wf = self._call_tool(13, "wait_for", {"text": "Loaded OK", "timeout": 3000})
        self.assertFalse(wf["isError"])
        self.assertEqual(json.loads(wf["content"][0]["text"])["matched"], "Loaded OK")

        # 13. close_page
        cl = self._call_tool(14, "close_page", {"pageId": 1})
        self.assertFalse(cl["isError"])
        pages_after = json.loads(self._call_tool(15, "list_pages")["content"][0]["text"])["pages"]
        self.assertEqual(pages_after, [])

    def test_error_codes(self):
        self.assertEqual(
            self._rpc({"jsonrpc": "2.0", "id": 50, "method": "nope"})["error"]["code"],
            -32601,
        )
        self.assertEqual(
            self._rpc({"jsonrpc": "2.0", "id": 51, "method": "tools/call", "params": {"name": "missing"}})["error"]["code"],
            -32602,
        )
        self.proc.stdin.write("{bad json\n")
        self.proc.stdin.flush()
        self.assertEqual(json.loads(self.proc.stdout.readline())["error"]["code"], -32700)


if __name__ == "__main__":
    unittest.main()
