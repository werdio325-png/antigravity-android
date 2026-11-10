#!/usr/bin/env python3
"""
agy_system.py - Unified CLI controller communicating with the Android System Gateway.
"""
import sys
import os
import argparse
import json

# Ensure modules/env is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_ENV_DIR = os.path.dirname(SCRIPT_DIR)
if MODULES_ENV_DIR not in sys.path:
    sys.path.insert(0, MODULES_ENV_DIR)

try:
    from bridge import client, commands
except ImportError:
    import bridge.client as client
    import bridge.commands as commands


def cmd_notify(args):
    if args.action == "cancel":
        res = client.send_command("notify", {"action": "cancel", "tag": args.tag, "id": args.id})
    else:
        payload = {
            "action": "post",
            "title": args.title,
            "message": args.message or "",
            "tag": args.tag,
            "channel": args.channel
        }
        if args.progress is not None:
            payload["progress"] = args.progress
        if args.ongoing:
            payload["ongoing"] = True
        res = client.send_command("notify", payload)
    
    if args.json:
        print(json.dumps(res, indent=2))
    elif res.get("ok"):
        print(f"[OK] Notification {args.action}ed (tag={args.tag})")
    else:
        print(f"[ERROR] Notification failed: {res.get('error', 'unknown error')}", file=sys.stderr)
        sys.exit(1)


def cmd_open(args):
    res = client.send_command("open_url", {"url": args.url})
    if args.json:
        print(json.dumps(res, indent=2))
    elif res.get("ok"):
        print(f"[OK] Opened URL: {args.url}")
    else:
        print(f"[ERROR] Failed to open URL: {res.get('error')}", file=sys.stderr)
        sys.exit(1)


def cmd_wakelock(args):
    action = "acquire" if args.state == "on" else "release"
    res = client.send_command("wakelock", {"action": action, "tag": args.tag})
    if args.json:
        print(json.dumps(res, indent=2))
    elif res.get("ok"):
        print(f"[OK] Wakelock {action}d (tag={args.tag})")
    else:
        print(f"[ERROR] Wakelock error: {res.get('error')}", file=sys.stderr)
        sys.exit(1)


def cmd_info(args):
    res = client.send_command("system_info")
    if args.json or not res.get("ok"):
        print(json.dumps(res, indent=2))
    else:
        print("=== Android System Info ===")
        print(f"  Battery:      {res.get('battery', '?')}% {'(Charging)' if res.get('charging') else ''}")
        print(f"  Network:      {res.get('network', 'unknown')}")
        print(f"  Storage Free: {res.get('storage_free_mb', '?')} MB")
        print(f"  Memory Free:  {res.get('memory_free_mb', '?')} MB")


def cmd_status(args):
    res = client.send_command("status")
    if args.json or not res.get("ok"):
        print(json.dumps(res, indent=2))
        if not res.get("ok"):
            sys.exit(1)
        return
    core = res.get("core") or {}
    ready = core.get("ready")
    state = "READY" if ready else ("RESTARTING" if core.get("restarting") else "STARTING/NOT READY")
    print("=== Antigravity Status ===")
    if res.get("app_version"):
        print(f"  App:          {res.get('app_version')}")
    print(f"  Core:         {state}")
    if core.get("last_error"):
        print(f"  Last error:   {core.get('last_error')}")


def cmd_doctor(args):
    if getattr(args, 'repair', False):
        res = client.send_command("repair", {})
    else:
        res = client.send_command("doctor", {})
    if args.json or not res.get("ok"):
        print(json.dumps(res, indent=2))
        if not res.get("ok"):
            sys.exit(1)
        return
    if res.get("actions"):
        print("=== Perbaikan Aman ===")
        for a in res.get("actions") or []:
            mark = "OK" if a.get("ok") else "GAGAL"
            print(f"  [{mark}] {a.get('action')}: {a.get('detail','')}")
        if res.get("note"):
            print(f"  Catatan: {res.get('note')}")
        return
    summary = res.get("summary") or {}
    print("=== Cek Kesehatan Antigravity ===")
    print(f"  Ringkasan: OK={summary.get('ok',0)}, Perlu perhatian={summary.get('warn',0)}, Bermasalah={summary.get('error',0)}")
    for c in res.get("checks") or []:
        lv = c.get("level", "ok").upper()
        print(f"  [{lv}] {c.get('title','')}")
        if c.get("detail"): print(f"        {c.get('detail')}")
        if c.get("fix"): print(f"        Saran: {c.get('fix')}")


def cmd_clipboard(args):
    if args.action == "set":
        text = args.text or sys.stdin.read()
        res = client.send_command("clipboard", {"action": "set", "text": text})
        if args.json:
            print(json.dumps(res, indent=2))
        elif res.get("ok"):
            print(f"[OK] Copied {len(text)} characters to clipboard.")
        else:
            print(f"[ERROR] Clipboard set failed: {res.get('error', 'unknown error')}", file=sys.stderr)
            sys.exit(1)
    else:
        res = client.send_command("clipboard", {"action": "get"})
        if args.json:
            print(json.dumps(res, indent=2))
        elif res.get("ok"):
            print(res.get("text", ""))
        else:
            print(f"[ERROR] Clipboard get failed: {res.get('error', 'unknown error')}", file=sys.stderr)
            sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Antigravity System Gateway Controller")
    parser.add_argument("--json", action="store_true", help="Output raw JSON")
    subparsers = parser.add_subparsers(dest="command")

    # notify
    p_notify = subparsers.add_parser("notify")
    p_notify.add_argument("action", choices=["post", "cancel"], default="post", nargs="?")
    p_notify.add_argument("-t", "--title", default="Antigravity")
    p_notify.add_argument("-m", "--message")
    p_notify.add_argument("--tag", default="agy_task")
    p_notify.add_argument("--id", type=int, default=1001)
    p_notify.add_argument("--progress", type=int, help="0-100 progress value")
    p_notify.add_argument("--ongoing", action="store_true")
    p_notify.add_argument("--channel", default="agy_tasks")
    p_notify.set_defaults(func=cmd_notify)

    # open
    p_open = subparsers.add_parser("open")
    p_open.add_argument("url")
    p_open.set_defaults(func=cmd_open)

    # wakelock
    p_wake = subparsers.add_parser("wakelock")
    p_wake.add_argument("state", choices=["on", "off"])
    p_wake.add_argument("--tag", default="agy_cli")
    p_wake.set_defaults(func=cmd_wakelock)

    # info
    p_info = subparsers.add_parser("info")
    p_info.set_defaults(func=cmd_info)

    # status
    p_status = subparsers.add_parser("status")
    p_status.set_defaults(func=cmd_status)

    # doctor
    p_doc = subparsers.add_parser("doctor")
    p_doc.add_argument("--repair", action="store_true", help="Run limited safe repairs")
    p_doc.set_defaults(func=cmd_doctor)

    # clipboard
    p_clip = subparsers.add_parser("clipboard")
    p_clip.add_argument("action", choices=["get", "set"])
    p_clip.add_argument("text", nargs="?", default="")
    p_clip.set_defaults(func=cmd_clipboard)

    args = parser.parse_args()
    if hasattr(args, "func"):
        args.func(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
