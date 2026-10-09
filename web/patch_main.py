#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js.

Locates the Appearance settings block and injects the Desktop Mode toggle
if not already present. Safe, idempotent, and resilient to upstream changes.
"""

import sys
import os
import re

def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Desktop Mode" in content or "ag_desktop_mode" in content:
        print("[web-patch] Desktop Mode toggle already present in main.js")
        return True

    # 1. State hook injection pattern in Appearance component (r4b)
    hook_pattern = re.compile(r'(var\s+[A-Za-z0-9_$]+=f!==3,\s*[A-Za-z0-9_$]+=f!==2,\s*[A-Za-z0-9_$]+=E=>\s*G=>\{E===r&&u\(G\)\};)')
    hook_match = hook_pattern.search(content)

    if not hook_match:
        print("[web-patch] hook pattern not matched", file=sys.stderr)
        return False

    orig_hook = hook_match.group(1)
    injected_hook = (
        "var [isDesktop,setIsDesktop]=(0,z.useState)(()=>{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        "var onDesktopToggle=(0,z.useCallback)(v=>{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
        + orig_hook
    )

    # 2. JSX element injection: find Theme element inside O0 container under Appearance
    # e.g.: z.createElement(O0,null,z.createElement(Rwb,{label:"Theme",...}))
    theme_pattern = re.compile(
        r'(z\.createElement\([A-Za-z0-9_$]+,null,z\.createElement\([A-Za-z0-9_$]+,\{label:"Theme",.*?onSelect:[A-Za-z0-9_$]+\}\))\)'
    )
    theme_match = theme_pattern.search(content)

    if not theme_match:
        print("[web-patch] Theme element pattern not found in Appearance", file=sys.stderr)
        return False

    orig_full = theme_match.group(0) # ends with ))
    orig_inner = theme_match.group(1) # up to before the last )
    injected_full = (
        orig_inner
        + ',z.createElement(CR,{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
        + 'rightElement:z.createElement(oQ,{checked:isDesktop,onCheckedChange:onDesktopToggle})}))'
    )

    content = content.replace(orig_hook, injected_hook, 1)
    content = content.replace(orig_full, injected_full, 1)

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

    print("[web-patch] Successfully patched Desktop Mode toggle into main.js (mobile by default, English)")
    return True

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
