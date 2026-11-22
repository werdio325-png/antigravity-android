---
name: android-browser
description: Automate an isolated headless Android WebView via `android_browser_automate` for web scraping, DOM interaction, JS evaluation, console log inspection, and PNG screenshots, and preview local dev servers safely on localhost.
---

# Android Browser Automation & Localhost Live Preview

Antigravity for Android provides two complementary browser capabilities without requiring desktop Chrome or Puppeteer:
1. **Headless Browser Subagent (`android_browser_automate` MCP tool)** — isolated offscreen Android WebView (no `AndroidBridge`, file access disabled, `http`/`https` only) controlled through `SystemGatewayServer`.
2. **Safe Localhost Live Preview** — inspect local web apps bound to `127.0.0.1` / `localhost` directly inside Antigravity.

## 1. Headless Automation (`android_browser_automate`)

All actions share a single lazy-initialized offscreen WebView session (default viewport `1080x1920`).

### Supported Actions
| Action | Required / Optional Args | Returns |
| :--- | :--- | :--- |
| `navigate` | `url`, `timeout_ms?`, `width?`, `height?` | `{ok, url, title, status}` |
| `get_text` | `selector?`, `max_chars?` | `{ok, text, url, title}` |
| `click` | `selector` | `{ok, clicked}` |
| `type` | `selector`, `text`, `clear?` | `{ok, typed}` |
| `eval` | `script` | `{ok, result}` |
| `screenshot` | `path?` (defaults to `cacheDir/browser_auto/<ts>.png`) | `{ok, path, width, height}` |
| `console_logs` | `clear?` | `{ok, logs}` |
| `close` | *(none)* | `{ok, closed}` |

### Recommended Workflow
1. **Navigate:** `android_browser_automate(action="navigate", url="http://127.0.0.1:5173", width=1080, height=1920)`
2. **Verify content & console errors:**
   - `android_browser_automate(action="get_text", max_chars=8000)`
   - `android_browser_automate(action="console_logs")`
3. **Interact & assert:**
   - `android_browser_automate(action="type", selector="input[name='q']", text="hello", clear=true)`
   - `android_browser_automate(action="click", selector="button[type='submit']")`
   - `android_browser_automate(action="eval", script="document.querySelectorAll('.result').length")`
4. **Capture visual evidence:**
   - `android_browser_automate(action="screenshot")` -> inspect the returned PNG path.
5. **Release memory when done:**
   - Always finish with `android_browser_automate(action="close")` so the offscreen WebView does not linger in RAM.

## 2. Live Localhost Preview

- Always bind dev servers (Vite, Next.js, Flask, FastAPI, `python -m http.server`) to `127.0.0.1` or `localhost` on safe ports (`3000`, `5173`, `8000`, `8080`). Never use reserved ports `45157`, `45158`, or `45160`.
- Users can view live localhost apps interactively via the in-app **Quick Controls -> Localhost Preview** button (`LocalPreviewDialog`, isolated WebView without native bridge) or loopback iframe previews.
- To verify a local dev server autonomously before asking the user to inspect it, navigate to `http://127.0.0.1:<port>` with `android_browser_automate`, check `console_logs` for runtime errors, and take a `screenshot`.
