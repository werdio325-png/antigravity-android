# ACP integration audit (Continuum 3.2.0-alpha.1)

What exists today, what Google actually ships, which integration shape is viable on
the Galaxy A54, and what this milestone does and does not deliver.

Everything about `agy` below was checked against Google's own live endpoints and
docs on 2026-11-14; nothing is inferred from the desktop app.

## 1. Continuum 3.1 architecture (verified in-tree)

```
MainActivity ──> CoreServerService (foreground service, wake lock)
                     └─> CoreProcessSupervisor ──> runtime/bin/liblanguage_server.so
                                                       (vendored, patched, glibc loader)
MainActivity ──> WebViewHost ──> loadDataWithBaseURL(https://127.0.0.1:45167/)
                     ├─ Android        (AndroidBridge: files, paths, theme, exec…)
                     ├─ AndroidUpdate  (updater)
                     └─ AndroidAgent   (NEW: ACP backend)
AgyWebViewClient ──> LocalAssetServer (filesDir/web, ETag revalidation)
```

* The UI is Antigravity's React bundle, loaded from app assets, based at the
  engine origin. It talks to the engine with ConnectRPC over localhost HTTPS and a
  WebSocket stream; `session_sync` (3.1) reconciles the view on resume.
* Conversation state is authoritative **inside the engine** (its own store under
  the app sandbox). The WebView is a view; nothing in the app owns a transcript.
* Workspaces are the engine's own concept; `filesDir` is `HOME`.

## 2. What Google ships (verified)

| Fact | Evidence |
| :--- | :--- |
| Official CLI `agy` exists, current published **1.5.9** | official updater manifest |
| **Android ARM64 build is officially published** | `manifests/android_arm64.json` → `cli_android_arm64.tar.gz`, SHA-512 published |
| Installer picks the Bionic build on Android | own installer: `uname -o = Android` → `os="android"`; comment: "Termux needs the Bionic build: Android's linker rejects glibc/musl ones" |
| Payload host | `storage.googleapis.com/antigravity-public/antigravity-cli/<version>/android-arm/…` |
| ACP server | `agy acp`, JSON-RPC over stdin/stdout (ACP v1) |
| ACP sessions | supports loading, resuming, listing, closing |
| Auth | cached from interactive `agy` / `agy acp --login`; `-32000 Authentication required`; methods include `oauth-personal`, `oauth-business`, `gemini-api-key`, `agent-platform`, `terminal-login` |
| Session options | Model + Thinking level (`Low`, `Medium`, `High`, `Extra`, `Max`) |
| Permissions | per-tool approval; `--dangerously-skip-permissions` exists but is never enabled by Continuum |
| Models | catalogue is account/plan dependent; Hydrogen available on Free/Pro/Ultra; Carbon Ultra/Enterprise; Argon Pro/Ultra |
| Hydrogen ids | `gemini-4-hydrogen`, tiers including `-high` and `-xhigh` (`--effort xhigh`) |

Manifest fetched during this audit:

```json
{"version":"1.5.9",
 "url":"https://storage.googleapis.com/antigravity-public/antigravity-cli/1.5.9-4515810895593472/android-arm/cli_android_arm64.tar.gz",
 "sha512":"b7f7ad4a…ebcd2c"}
```

## 3. Runtime options compared

**Option A — managed Linux/PRoot userland.** Would work for the *Linux* asset, but
it is strictly worse here: the officially published Android build is already
Bionic, and the installer says the Android linker rejects glibc/musl builds. PRoot
adds a second libc, a translation layer, more memory, slower I/O and a large
correctness surface (PTY, signals, /proc) on an 8 GB phone.

**Option B — native Android process (chosen).** Spawn `agy acp` directly from the
app sandbox (as the official installer expects on Android), no root, no PRoot.
Isolation is the app's own uid plus scoped workspace directories; credentials stay
in the app's `HOME`; the process is a single supervised child.

Decision: **B**, because it uses Google's actual Android build, avoids a
translation layer, and keeps memory/CPU bounded on the target device. A is
documented as a fallback only if the Bionic build proves unusable on-device.

## 4. Implemented in this milestone

* `acp/JsonRpcPeer` — NDJSON JSON-RPC 2.0, request correlation, ordered
  notifications, agent→client requests, duplicate/late responses ignored, clean
  EOF handling, pending requests failed (never replayed).
* `acp/AcpClient` — `initialize`, `authenticate`, `session/new`, `session/load`,
  `session/prompt`, `session/cancel`, `session/set_config_option`; refuses a newer
  protocol version instead of reinterpreting it; answers `session/request_permission`
  with the option the agent actually offered (deny by default).
* `acp/AcpModelCatalog` — models and thinking levels from the agent's own session
  options; statuses `available | not_supported | account_restricted |
  quota_restricted | unknown`; a model becomes `available` only after a real turn.
* `acp/CliRelease` / `acp/CliInstaller` — manifest parsing, https + Google-host
  enforcement, SHA-512 verification, tar.gz unpack, exec bit, version marker. Never
  bundles or mirrors the binary, never executes before verification.
* `acp/AcpRuntime` — one supervised `agy acp` process, `HOME` in the app sandbox,
  session create/load with workspace, model/thinking selection, cancel, restart,
  status reporting, no silent approvals, no automatic re-sends.
* `backend/BackendSettings` — reversible backend choice, ACP session/workspace/model
  stored in a separate namespace from the embedded engine.
* `acp/AcpBridge` — token-gated JS surface (`AndroidAgent`), plus
  `__APP_CONFIG__.backendMode` / `.acp` for the UI.
* `web/src/patches/backend_ui` — Settings panel: active backend, CLI version,
  process/ACP/auth state, discovered models with honest status, switch backends.

## 5. What this milestone does **not** do

* **The chat surface is still the embedded engine's UI.** ACP turns stream events
  and update the diagnostic panel, but there is no ACP conversation renderer yet.
  Milestone 3.2.0-beta.1 is the chat/tool UI on top of `AcpRuntime`.
* **No device run.** No Galaxy A54, no signed-in Google account and no ARM64
  Android runtime exist in this environment, so no ACP session has ever been
  started against the real `agy`, and no Hydrogen inference has been attempted.
* **Google sign-in is not implemented in-app.** The CLI's own flow
  (`agy` interactive or `agy acp --login`) remains the only path; the app reports
  `authRequired` and lists the agent's advertised methods. It never synthesises a
  token.

## 6. Plan to 3.2.0

1. **alpha.2** — provision the CLI on-device, start `agy acp`, complete sign-in via
   the CLI's own terminal flow, verify `initialize`/`session/new` shapes on real
   hardware.
2. **beta.1** — ACP conversation surface (message chunks, tool cards, diffs,
   permission prompts), workspace selection, model/thinking pickers wired to the
   catalogue.
3. **beta.2** — Hydrogen High on a Jio Google AI Pro account: discovery, selection,
   real inference, tool call; capture any server rejection verbatim in
   `docs/HYDROGEN_COMPATIBILITY_REPORT.md`.
4. **3.2.0** — full regression plus device matrix, signed APK, checksums.

## 7. Security posture

* No root, no PRoot, no `--dangerously-skip-permissions`, no credential forging.
* Only Google's https distribution host is accepted; SHA-512 must match the
  manifest before the binary is written or executed.
* Tool permissions are surfaced, denied by default, and never auto-approved by the
  host.
* ACP state (`session_id`, `workspace`, `model`) is namespaced separately so it
  cannot resume or overwrite embedded-engine conversations.
