---
name: android-daemon
description: Schedule and manage autonomous background AI tasks, cron jobs, interval monitors, file/GitHub/battery watchers, and webhooks with `agy-daemon` and the `android_schedule_task` MCP tool.
---

# Autonomous Background Tasks (`agy-daemon`)

`agy-daemon` is Antigravity's ultra-lightweight (<10MB RAM, 0.0% idle CPU) background scheduler and watcher running under Android's Foreground Service Partial WakeLock so tasks execute even with the screen off.

## 1. How AI Prompt Tasks Execute

When a task has `prompt` set (and no shell `command`):
1. `executor.py` reads `$HOME/logs/core.state.json` (`https_port`, `csrf_token`, `base_url`) published by `CoreProcessSupervisor`.
2. It calls `exa.language_server_pb.LanguageServerService/StartCascade` (passing `workspaceUris` when `project` is set, unless `conversation_id` is already provided) and `SendUserCascadeMessage` over ConnectRPC.
3. If the core server is stopped or unreachable, it safely falls back to posting an Android status notification (`agy-notify`) without shell interpolation and logs the reason.

## 2. Managing Tasks via MCP (`android_schedule_task`)

Prefer `android_schedule_task` for structured task management:

- **Schedule a delayed AI check-in:**
  `android_schedule_task(action="add", name="Verify Build", delay="20m", prompt="Inspect latest build logs and summarize any failures", project="/storage/emulated/0/Documents/myapp", wake_app=true)`
- **Schedule a recurring cron job:**
  `android_schedule_task(action="add", name="Morning Summary", cron="0 9 * * *", prompt="Summarize git status and open TODOs in the workspace")`
- **Watch a file for changes:**
  `android_schedule_task(action="add", name="Auto Test", watch_file="/storage/emulated/0/Documents/myapp/src/main.py", command="python -m unittest discover")`
- **Watch GitHub PRs or battery:**
  - `github_pr: "owner/repo"`, `interval: "5m"`
  - `battery_below: 15`, `command: "agy-notify post -t 'Low Battery' -m 'Pause heavy builds'"`
- **Inspect / control tasks:**
  - `action="list"` (`active_only=true`)
  - `action="pause"` / `action="resume"` / `action="remove"` / `action="run_now"` with `id="<task_id_or_name>"`
  - `action="status"`

## 3. CLI Reference (`agy-daemon`)

```bash
agy-daemon status
agy-daemon start | stop | restart
agy-daemon task list --json
agy-daemon task add --name "Daily Check" --cron "0 8 * * *" --prompt "Run tests and report regressions"
agy-daemon task run-now "Daily Check"
agy-daemon task logs "Daily Check"
agy-daemon task pause "Daily Check"
agy-daemon task remove "Daily Check"
```

## 4. Best Practices
- Always pass `--project` when scheduling AI prompt tasks tied to a repository so the new cascade opens with the right workspace URI.
- Ensure `agy-daemon status` reports `RUNNING`; run `agy-daemon start` if stopped.
- Keep shell commands self-contained and quote paths in `/storage/emulated/0/...` (never rely on executable bits on shared storage; invoke `bash script.sh` or `python script.py`).
