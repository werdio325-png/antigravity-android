---
name: android-device
description: Control and inspect the host Android device via the native `android-device` MCP server (`agy-mcp-android`), Shizuku (`rish`), and `SystemGatewayServer`. Use when querying battery/network/storage, reading/writing clipboard, posting interactive Approve/Reject notifications, toggling torch/haptics/toasts, or running elevated ADB commands (`screencap`, `logcat`, `pm`, `am`, `dumpsys`).
---

# Android Device Control (`android-device` MCP & Shizuku)

Antigravity for Android pre-configures the `android-device` stdio MCP server (`$PREFIX/bin/agy-mcp-android`) backed by the local `SystemGatewayServer` (`127.0.0.1:<port>`, port stored in `$HOME/logs/gateway.port`, default `45160`).

## 1. Preferred Interface: `android-device` MCP Tools

Always prefer the structured MCP tools over raw shell scripts when available:

| Tool | Purpose | Key Arguments |
| :--- | :--- | :--- |
| `android_device_info` | Battery %, charging, network, free storage & memory | *(none)* |
| `android_notify` | Post/update/cancel notifications, approval presets (`Approve`/`Deny`/`Deny w/ Comment` inline reply) | `title`, `message`, `approval`, `type`, `tag`, `channel` (`agy_tasks`/`agy_alerts`/`agy_subagents`), `progress`, `request_id`, `actions` |
| `android_wait_approval` | Wait for user's Approve, Deny, or Deny w/ Comment reply from notification shade | `request_id`, `timeout_sec` (default 60), `poll_interval_ms` |
| `android_subagent_notify` | Low-buzz subagent/swarm progress notifications with Cancel button (`CancelCascadeInvocation`) | `action` (`post`/`done`/`cancel`), `subagent_id`, `cascade_id`, `role`, `message`, `progress`, `cancellable` |
| `android_voice_service` | Control Android `VoiceForegroundService` (audio focus, speakerphone, Mute/Stop notification) | `action` (`start`/`stop`/`mute`/`unmute`/`toggle_mute`/`status`), `status_text`, `speakerphone` |
| `android_clipboard_get` | Read plain text from Android clipboard | *(none)* |
| `android_clipboard_set` | Write plain text to Android clipboard | `text` |
| `android_toast` | Show brief on-screen Toast message | `message`, `duration` (`short`/`long`) |
| `android_vibrate` | Trigger haptic feedback | `duration_ms` (10–5000) |
| `android_torch` | Turn camera flashlight on or off | `enabled` (`true`/`false`) |
| `android_shizuku_exec` | Run elevated `uid=2000` ADB shell command via `rish` | `command`, `timeout_sec` |

### Human-in-the-Loop Approval Pattern (with Inline Comment Reply)
Before running destructive or high-impact actions while the user is away from the chat window:
1. Call `android_notify` with `approval: true` (auto-adds **Approve**, **Deny**, and **Deny w/ Comment** `RemoteInput` buttons in `agy_alerts`):
   ```json
   {
     "approval": true,
     "title": "Approve Production Deploy?",
     "message": "Ready to push build #12 to production.",
     "request_id": "deploy_prod_01",
     "reply_label": "Reason or instructions..."
   }
   ```
2. Call `android_wait_approval` with `request_id: "deploy_prod_01"` and `timeout_sec: 120`.
3. Inspect `decision` (`"approve"`, `"deny"`, or `"deny_with_comment"`) and optional `comment` feedback from the user before proceeding.

## 2. Elevated Operations via Shizuku (`android_shizuku_exec` / `rish`)

Use `android_shizuku_exec` (or `rish -c "<cmd>"` in terminal) for ADB-privileged operations:

- **Capture screen:**
  `screencap -p /storage/emulated/0/Download/screen.png`
- **Inspect Android logs:**
  `logcat -d -t 150 | grep -iE "FATAL|AndroidRuntime|Agy"`
- **Manage packages (`pm`):**
  `pm list packages -3` / `pm install -r "/storage/emulated/0/Download/app.apk"`
- **Launch activities (`am`) & inspect services (`dumpsys`):**
  `am start -n com.example.app/.MainActivity` / `dumpsys battery`

If Shizuku reports `Server is not running`, tell the user to start the Shizuku service and retry.

## 3. Direct `SystemGatewayServer` HTTP API (Fallback)

If calling from a shell script when MCP is not in scope:
```bash
GW_PORT="$(cat "$HOME/logs/gateway.port" 2>/dev/null || echo 45160)"
curl -sS "http://127.0.0.1:${GW_PORT}/gateway/status"
curl -sS -X POST "http://127.0.0.1:${GW_PORT}/gateway" \
  -H "Content-Type: application/json" \
  -d '{"command":"system_info","payload":{}}'
```
