#!/usr/bin/env python3
"""mock_agent.py - a deterministic ACP agent for Continuum's adapter tests.

Implements the subset of the Agent Client Protocol that Continuum drives:
initialize, authenticate, session/new, session/load, session/prompt,
session/cancel, session/set_config_option, plus session/update notifications and
agent->client permission requests.

Behaviour switches (environment):
  MOCK_AUTH=fail          authenticate returns -32000 "Authentication required"
  MOCK_PROTOCOL=2         initialize negotiates protocol 2 (client must refuse)
  MOCK_MODEL_REJECT=plan  session/prompt fails with a plan/entitlement error
  MOCK_MODEL_REJECT=quota session/prompt fails with a quota error
  MOCK_MODEL_REJECT=model session/prompt fails with an unsupported-model error
  MOCK_HANG=1             session/prompt waits for session/cancel
  MOCK_DIE=1              exit immediately after the first prompt
  MOCK_REPLAY=1           re-send the last chunk (duplicate notification)

This is a test double. It is never shipped in the APK and never talks to Google.
"""
import json
import os
import sys
import threading
import time

PROTOCOL_VERSION = int(os.environ.get("MOCK_PROTOCOL", "1"))
SESSIONS = {}
LOCK = threading.Lock()
CANCELLED = set()


def send(payload):
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def respond(request_id, result):
    send({"jsonrpc": "2.0", "id": request_id, "result": result})


def error(request_id, code, message):
    send({"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}})


def notify(method, params):
    send({"jsonrpc": "2.0", "method": method, "params": params})


def request_client(request_id, method, params):
    send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})


def session_options():
    return [
        {
            "id": "model",
            "name": "Model",
            "type": "select",
            "currentValue": "gemini-4-hydrogen",
            "options": [
                {"value": "gemini-4-hydrogen", "name": "Gemini 4 Hydrogen"},
                {"value": "gemini-4-hydrogen-high", "name": "Gemini 4 Hydrogen High"},
                {"value": "gemini-4-hydrogen-xhigh", "name": "Gemini 4 Hydrogen Extra High"},
                {"value": "gemini-4-argon-high", "name": "Gemini 4 Argon High"},
                {"value": "gemini-3.8-flash", "name": "Gemini 3.8 Flash"},
            ],
        },
        {
            "id": "thinking_level",
            "name": "Thinking level",
            "type": "select",
            "currentValue": "high",
            "options": [
                {"value": "low", "name": "Low"},
                {"value": "medium", "name": "Medium"},
                {"value": "high", "name": "High"},
                {"value": "extra", "name": "Extra"},
            ],
        },
    ]


def update(session_id, payload):
    notify("session/update", {"sessionId": session_id, "update": payload})


def handle_prompt(request_id, params):
    session_id = params.get("sessionId")
    if session_id not in SESSIONS:
        error(request_id, -32602, "unknown session")
        return

    reject = os.environ.get("MOCK_MODEL_REJECT", "")
    if reject == "plan":
        error(request_id, 403, "This model is not included in your current plan")
        return
    if reject == "quota":
        error(request_id, 429, "Quota exceeded for this model")
        return
    if reject == "model":
        error(request_id, -32602, "Unsupported model for this runtime")
        return

    if os.environ.get("MOCK_DIE") == "1":
        sys.exit(0)

    update(session_id, {"sessionUpdate": "agent_message_chunk",
                        "content": {"type": "text", "text": "Planning the change."}})
    update(session_id, {"sessionUpdate": "agent_thought_chunk",
                        "content": {"type": "text", "text": "Reading the workspace."}})
    update(session_id, {"sessionUpdate": "tool_call",
                        "toolCallId": "call-1",
                        "title": "read src/Main.kt",
                        "kind": "read",
                        "status": "pending",
                        "locations": [{"path": "/work/app/src/Main.kt"}]})

    # Permission request (agent -> client), answered by Continuum's handler.
    request_client(9001, "session/request_permission", {
        "sessionId": session_id,
        "toolCall": {"toolCallId": "call-1", "title": "read src/Main.kt", "kind": "read"},
        "options": [
            {"optionId": "allow-once", "name": "Allow", "kind": "allow_once"},
            {"optionId": "reject-once", "name": "Deny", "kind": "reject_once"},
        ],
    })

    if os.environ.get("MOCK_HANG") == "1":
        # A real agent keeps reading stdin while a turn runs (to receive
        # session/cancel), so wait on a worker thread instead of blocking the
        # read loop.
        def wait_for_cancel():
            deadline = time.time() + 20
            while time.time() < deadline and session_id not in CANCELLED:
                time.sleep(0.02)
            respond(request_id, {"stopReason": "cancelled"})

        threading.Thread(target=wait_for_cancel, daemon=True).start()
        return

    if os.environ.get("MOCK_REPLAY") == "1":
        update(session_id, {"sessionUpdate": "agent_message_chunk",
                            "content": {"type": "text", "text": "Planning the change."}})

    update(session_id, {"sessionUpdate": "tool_call_update",
                        "toolCallId": "call-1",
                        "status": "completed",
                        "content": [{"type": "text", "text": "package ok"}]})
    update(session_id, {"sessionUpdate": "agent_message_chunk",
                        "content": {"type": "text",
                                    "text": "Done: fun validatePackageName(value: String): Boolean"}})
    respond(request_id, {"stopReason": "end_turn"})


def main():
    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") or {}

        if method is None and request_id is not None:
            # Response from the client to our permission request.
            continue

        if method == "initialize":
            result = {
                "protocolVersion": PROTOCOL_VERSION,
                "agentInfo": {"name": "mock-agy", "version": "1.5.9"},
                "agentCapabilities": {
                    "loadSession": True,
                    "promptCapabilities": {"image": False, "embeddedContext": False},
                    "auth": {"logout": True},
                },
                "authMethods": [
                    {"id": "oauth-personal", "name": "Log in with Google",
                     "description": "Browser sign-in"},
                    {"id": "gemini-api-key", "name": "Gemini API key"},
                ],
            }
            respond(request_id, result)

        elif method == "authenticate":
            if os.environ.get("MOCK_AUTH") == "fail":
                error(request_id, -32000, "Authentication required")
            else:
                respond(request_id, {})

        elif method == "session/new":
            cwd = params.get("cwd", "")
            session_id = "sess-%d" % (len(SESSIONS) + 1)
            with LOCK:
                SESSIONS[session_id] = {"cwd": cwd, "model": "gemini-4-hydrogen"}
            respond(request_id, {"sessionId": session_id, "configOptions": session_options()})

        elif method == "session/load":
            session_id = params.get("sessionId", "")
            if session_id not in SESSIONS:
                error(request_id, -32602, "unknown session")
            else:
                respond(request_id, {"sessionId": session_id, "configOptions": session_options()})

        elif method == "session/prompt":
            handle_prompt(request_id, params)

        elif method == "session/cancel":
            CANCELLED.add(params.get("sessionId"))

        elif method == "session/set_config_option":
            session_id = params.get("sessionId")
            options = session_options()
            for option in options:
                if option["id"] == params.get("configId"):
                    option["currentValue"] = params.get("value")
            if session_id in SESSIONS:
                SESSIONS[session_id]["model"] = params.get("value")
            respond(request_id, {"configOptions": options})

        elif method is not None:
            error(request_id, -32601, "Method not found: %s" % method)


if __name__ == "__main__":
    main()
