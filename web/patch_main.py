#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic, resilient patcher for web/generated/main.js.

Locates the Appearance settings block and injects the Desktop Mode toggle
if not already present. Safe, idempotent, and graceful when upstream changes:
if minified signatures shift, it warns and leaves main.js untouched so the
build still succeeds (web/generated/patches/mobile_bridge.js provides the
resilient Quick Controls fallback).
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

IDENT_RE = r"[A-Za-z0-9_$]+"


def _identifier_declared(content, ident):
    """Verify a minified component identifier is declared in the bundle."""
    if not ident or not re.fullmatch(IDENT_RE, ident):
        return False
    pattern = re.compile(
        r"(?:^|[;,{\s])(?:var|let|const|function|class)\s+" + re.escape(ident) + r"(?=\s*[=(;,{])"
    )
    return pattern.search(content) is not None


def _detect_setting_components(content, react_id):
    """Dynamically locate the setting row and switch components around rightElement:."""
    escaped_react = re.escape(react_id)
    combo_re = re.compile(
        escaped_react
        + r"\.createElement\(("
        + IDENT_RE
        + r"),\{label:[^}]*rightElement:"
        + escaped_react
        + r"\.createElement\(("
        + IDENT_RE
        + r"),\{checked:"
    )

    for match in re.finditer(r"rightElement:", content):
        idx = match.start()
        window = content[max(0, idx - 240) : min(len(content), idx + 180)]
        found = combo_re.search(window)
        if found:
            item_comp = found.group(1)
            switch_comp = found.group(2)
            if _identifier_declared(content, item_comp) and _identifier_declared(content, switch_comp):
                return item_comp, switch_comp
    return None, None


def _verify_js_syntax(js_path):
    """Optionally verify patched JS syntax using `node --check` when node is available."""
    node_bin = shutil.which("node")
    if not node_bin:
        return True, "node not found; skipped syntax verification"
    try:
        proc = subprocess.run(
            [node_bin, "--check", js_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
        )
    except Exception as exc:
        return False, f"node --check invocation error: {exc}"
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip()
        return False, f"node --check failed: {err}"
    return True, "node --check ok"


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Desktop Mode" in content or "ag_desktop_mode" in content:
        print("[web-patch] Desktop Mode toggle already present in main.js")
        return True

    # 1. JSX Theme element injection: find Theme element in Appearance
    theme_idx = content.find('{label:"Theme"')
    theme_match = None
    react_id = None
    if theme_idx != -1:
        window_start = max(0, theme_idx - 100)
        window_end = min(len(content), theme_idx + 500)
        window_text = content[window_start:window_end]
        theme_pattern = re.compile(
            r"(" + IDENT_RE + r")\.createElement\((" + IDENT_RE + r'),\{label:"Theme",.*?onSelect:' + IDENT_RE + r"\}\)"
        )
        theme_match = theme_pattern.search(window_text)
        if theme_match:
            react_id = theme_match.group(1)

    # 2. State hook anchor in Appearance component:
    # Prefer anchoring right before `return <react_id>.createElement(...{title:"Appearance"}`
    # (works across v2.11.0 and v2.34.0+); fallback to legacy var ...!==3,...!==2 pattern.
    hook_anchor = None
    if react_id and theme_idx != -1:
        app_window_start = max(0, theme_idx - 400)
        app_window = content[app_window_start:theme_idx]
        ret_pattern = re.compile(
            r"(;return\s+" + re.escape(react_id) + r"\.createElement\([^;]{0,160}\{title:\"Appearance\"\})"
        )
        ret_match = ret_pattern.search(app_window)
        if ret_match:
            hook_anchor = ret_match.group(1)

    if not hook_anchor:
        hook_pattern = re.compile(
            r"(var\s+"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"!==3,\s*"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"!==2,\s*"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"=>\s*"
            + IDENT_RE
            + r"=>\{.*?\};)"
        )
        hook_match = hook_pattern.search(content)
        if hook_match:
            hook_anchor = hook_match.group(1)

    item_comp, switch_comp = (
        _detect_setting_components(content, react_id) if react_id else (None, None)
    )

    if not hook_anchor or not theme_match or not react_id or not item_comp or not switch_comp:
        print(
            "[web-patch] WARNING: upstream main.js signatures not matched "
            f"(hook={bool(hook_anchor)}, theme={bool(theme_match)}, "
            f"item={item_comp}, switch={switch_comp}); "
            "leaving main.js unpatched (mobile_bridge.js provides resilient fallback controls).",
            file=sys.stderr,
        )
        return True

    hook_code = (
        f"var [isDesktop,setIsDesktop]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        f"var onDesktopToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
    )
    if hook_anchor.startswith(";return"):
        injected_hook = ";" + hook_code + hook_anchor[1:]
    else:
        injected_hook = hook_code + hook_anchor

    orig_theme = theme_match.group(0)
    injected_theme = (
        orig_theme
        + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
        + f"rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})"
    )

    patched = content.replace(hook_anchor, injected_hook, 1)
    patched = patched.replace(orig_theme, injected_theme, 1)

    target_dir = os.path.dirname(os.path.abspath(file_path))
    tmp_path = None
    try:
        fd, tmp_path = tempfile.mkstemp(prefix=".patch_main_", suffix=".js", dir=target_dir)
        with os.fdopen(fd, "w", encoding="utf-8") as tmp_f:
            tmp_f.write(patched)

        ok, msg = _verify_js_syntax(tmp_path)
        if not ok:
            print(
                f"[web-patch] WARNING: patched main.js failed syntax check ({msg}); "
                "keeping unpatched copy (mobile_bridge.js provides resilient fallback controls).",
                file=sys.stderr,
            )
            os.remove(tmp_path)
            tmp_path = None
            return True

        os.replace(tmp_path, file_path)
        tmp_path = None
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass

    print(
        f"[web-patch] Successfully patched Desktop Mode toggle into main.js "
        f"(react={react_id}, item={item_comp}, switch={switch_comp}; {msg})"
    )
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
