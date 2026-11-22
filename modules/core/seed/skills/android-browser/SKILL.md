---
name: android-browser
description: Automate an isolated headless Android WebView via the pre-registered `chrome_devtools` MCP server (`agy-mcp-browser`) or `android_browser_automate` for web scraping, DOM snapshots, clicking/filling, JS evaluation, console logs, PNG screenshots, and localhost live preview.
---

# Android Browser Automation & Localhost Live Preview

Antigravity for Android pre-configures **two** native browser automation interfaces backed by an isolated offscreen Android WebView (no `AndroidBridge`, file access disabled, `http`/`https` only) via `SystemGatewayServer`, requiring zero desktop Chrome or Node binaries:
1. **`chrome_devtools` MCP Server (`$HOME/runtime/bin/agy-mcp-browser`)** — seeded globally in `$HOME/.gemini/config/mcp_config.json`, exposing standard Chrome DevTools MCP tool names (`navigate_page`, `new_page`, `list_pages`, `select_page`, `take_snapshot`, `take_screenshot`, `click`, `fill`, `press_key`, `evaluate_script`, `list_console_messages`, `wait_for`, `close_page`).
2. **`android-device` MCP Server (`android_browser_automate`)** — direct single-tool interface to `browser_auto`.

## 1. Preferred Workflow (`chrome_devtools` MCP)

1. **Open page:** `navigate_page(url="https://example.com")` (or `new_page(url="http://127.0.0.1:5173")`)
2. **Inspect interactive DOM with `uid`s:** `take_snapshot()` -> returns page title/URL, interactive elements (`uid=e1 link "..."`, `uid=e2 textbox "..."`), and readable text.
3. **Interact:**
   - `fill(uid="e2", value="search query")`
   - `click(uid="e1")` or `press_key(key="Enter")`
   - `wait_for(text="Results", timeout=10000)`
   - `evaluate_script(function="() => document.title")`
4. **Capture screenshot & console logs:**
   - `take_screenshot()` (returns saved PNG path + inline image block)
   - `list_console_messages(types=["error", "warning"])`
5. **Release memory when done:** Always call `close_page()` (or `android_browser_automate(action="close")`) so the offscreen WebView does not linger in RAM.

## 2. Direct Fallback (`android_browser_automate`)

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
