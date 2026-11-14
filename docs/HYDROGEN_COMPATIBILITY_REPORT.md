# Gemini 4 Hydrogen compatibility report

Status: **not verified on a device.** Nothing in this report claims Hydrogen works
for a specific account. The tests below require hardware and a real Google account
that this build environment does not have.

## What is established

| Item | Value | How established |
| :--- | :--- | :--- |
| CLI version targeted | 1.5.9 (`android_arm64`) | Google's published updater manifest |
| Engine/runtime | Official `agy`, Bionic Android build, launched by the app | official installer's Android branch |
| Protocol | ACP v1, JSON-RPC over stdio (`agy acp`) | official CLI docs |
| Transport tested | Yes, against a mock ACP agent (24 protocol tests) | `tools/acp_test/run.sh` |
| Model discovery mechanism | agent's `session/new` config options | ACP docs + implemented parser |
| Candidate Hydrogen ids | `gemini-4-hydrogen`, `gemini-4-hydrogen-high`, `gemini-4-hydrogen-xhigh` | Google model docs (`--effort xhigh`, `--model gemini-4-hydrogen-xhigh`) |
| Documented plan access | Hydrogen: Free, AI Pro, Ultra, Enterprise; Carbon: Ultra/Enterprise; Argon: Pro/Ultra | Google model availability table |
| Account tested | **none** | no credentials available here |
| Actual inference on Hydrogen | **not attempted** | no device, no account |
| Jio Google AI Pro entitlement | **not tested** | requires the user's account |

Consequence for the fork's UI: the model list shows what the CLI reports, each
entry starts as `unknown` ("offered by the installed CLI; not yet exercised for
this account"), and only becomes `available` after a successful turn. A rejection
is classified as `account_restricted`, `quota_restricted` or `not_supported` using
the server's own error, never hidden.

## Tests A–E: procedure and expected evidence

Run these on the Galaxy A54 with the ACP backend selected after installing the CLI.
Record the CLI version from Settings → AI Backend.

**A — model discovery.** Start the ACP backend and open Settings → AI Backend.
Capture the full model list and thinking levels (they come from `session/new`
config options). Alternatively run in a terminal:
`agy models` (documented model listing) and record its exact output.
*Pass criterion:* Hydrogen entries are either present with exact ids or absent;
either way the list is recorded verbatim.

**B — Hydrogen High selection.** Select `gemini-4-hydrogen-high` in the model list
(or `agy --model gemini-4-hydrogen-high` in the CLI). *Pass criterion:* the session
option reports the new current value and the app shows the id without inventing a
label.

**C — actual inference.** Send:
"Create a Kotlin utility function that validates an Android package name, with unit
tests and clear error handling." *Pass criterion:* a real response containing Kotlin
code. This is the only thing that proves entitlement.

**D — tool execution.** Ask for a change to a file in a test workspace and approve
the permission prompt. *Pass criterion:* the tool call appears, the file changes,
the diff is reported by the agent.

**E — entitlement verification (only if C fails).** Record verbatim:
* HTTP / JSON-RPC error code and message,
* whether the CLI reported `Authentication required` (-32000),
* the account plan shown in the CLI (`/usage` for quota),
* the exact model id used.
Do not retry through a bypass. Classification in the app is automatic from the
message (`quota`, `plan/subscription/permission/region`, `not available/unsupported`).

**Jio Google AI Pro.** Hydrogen is documented as available on Google AI Pro, so
Hydrogen High is expected to be reachable; Argon is restricted to paid Pro plans or
trials with eligible premium devices, and Carbon is Ultra/Enterprise only. A Jio
promotional Pro subscription may or may not be treated as a paid plan by the
backend. Only test C settles it. If Hydrogen is rejected, the app will show
`account_restricted` with Google's own reason, and the embedded engine remains
fully functional.

## What cannot be claimed

* That Hydrogen High works. No turn was executed.
* That the Android build of `agy` runs inside Continuum. It is published and
  provisioned by verified download only; execution is untested here.
* Any Jio entitlement outcome.

## Next step

`docs/ACP_INTEGRATION_AUDIT.md` §6 lists the milestones; the first device session
should fill this table with the real values and replace "not attempted" lines with
captured evidence.
