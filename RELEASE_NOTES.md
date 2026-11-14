## Continuum 3.2.0-alpha.1 — official Antigravity CLI + ACP foundation

**Pre-release.** This is an alpha: the ACP backend exists and is tested against the
protocol, but it has not been run against Google's real CLI on a device, and the
conversation UI still belongs to the embedded engine. Do not treat it as a working
ACP chat client.

### Verified findings
- Google publishes an official **Android ARM64** build of Antigravity CLI: current
  version **1.5.9**, distributed from Google's own endpoint with a published
  **SHA-512**. Google's installer selects this Bionic build when `uname -o` is
  Android, so a Linux/PRoot layer is unnecessary.
- `agy acp` is a real ACP v1 server over stdio, with session load/resume/list/close,
  per-tool permission prompts, and session options for **model** and **thinking
  level**.

### Added
- **ACP protocol layer** (pure Java, no Android dependencies): JSON-RPC 2.0 over
  stdio with request correlation, ordered notifications, agent-initiated permission
  requests answered with the option the agent offered (deny by default), refusal of
  newer protocol versions, and no automatic retries after a failure.
- **Dynamic model catalogue** from the agent's own session options. Statuses are
  `unknown` until a real turn succeeds, then `available`; rejections are classified
  as `account_restricted`, `quota_restricted` or `not_supported`. Nothing is ever
  labelled available because it appears in a list.
- **Verified CLI provisioning**: manifest from Google's updater, https-only,
  Google-host-only, SHA-512 must match before the archive is unpacked, binary
  installed into app-private storage with a version marker. No bundled or mirrored
  binaries, no execution before verification.
- **Backend abstraction**: embedded engine (default, untouched) or official CLI
  over ACP, switchable and reversible in Settings → AI Backend, with ACP session,
  workspace and model stored in a separate namespace so the two backends cannot
  resume each other's conversations.
- **Runtime panel** showing CLI version, process and ACP state, authentication
  requirement, session, workspace and last synchronization.
- **24 automated ACP tests** (`tools/acp_test/run.sh`) covering initialization,
  auth failure, session setup/restore, prompt streaming, tool calls, file updates,
  permission allow/deny, cancellation, process death, disconnect, duplicate and
  retry suppression, workspace isolation, catalogue discovery, Hydrogen High
  selection, entitlement and quota classification, and release-manifest trust.

### Not working yet (by design, stated plainly)
- No ACP conversation renderer: agent events reach the host, but chat still renders
  through the embedded engine.
- No in-app Google sign-in: the CLI's own `agy` / `agy acp --login` flow is used and
  the app reports `authRequired`.
- No on-device verification and **no Hydrogen inference has been attempted**. See
  `docs/HYDROGEN_COMPATIBILITY_REPORT.md` for the exact tests and what evidence
  would count.
- Hydrogen/Carbon remain unavailable in the embedded engine (2.11.0); the ACP path
  is how they become reachable, subject to Google's account eligibility.
