#!/usr/bin/env python3
"""
Antigravity Touch UI & Model Thinking Level Selector Patcher

Fixes touch interaction for models with thinking/effort levels (e.g. Gemini 3.8 Flash).

Root Cause:
In web_ui/main.js, the model item for models with thinking efforts had:
  onClick: h ? void 0 : () => { var y = v.byEffort.get(w); y && b(y) }
where h = rH() checks `mobileLayout` feature flag. In Antigravity Mobile, the IDE
operates with desktop layout flags (binaryType: "Ide"), so `h` evaluates to false.
On a touchscreen, tapping the model fires a click event, which invoked this handler,
immediately selecting `w` (which defaults to "medium" via oHb) and closing the menu.
As a result, touch users could never open the thinking level submenu.

Solution:
Change `onClick:h?void 0:()=>{var y=v.byEffort.get(w);y&&b(y)}` to `onClick:void 0`.
On touch press (and mouse click), the SubmenuTrigger (KMa) opens the thinking level
submenu (Low / Medium / High) without prematurely auto-selecting Medium.
"""

import os
import sys

TARGET_STR = "onClick:h?void 0:()=>{var y=v.byEffort.get(w);y&&b(y)}"
PATCHED_STR = "onClick:void 0"

def patch_main_js(main_js_path):
    print(f"[patch_touch] Inspecting {main_js_path}...")
    if not os.path.isfile(main_js_path):
        print(f"[-] [patch_touch] File not found: {main_js_path}")
        return False

    with open(main_js_path, "r", encoding="utf-8") as f:
        content = f.read()

    if TARGET_STR not in content:
        if PATCHED_STR in content:
            print("[+] [patch_touch] File already patched with touch-friendly effort selector.")
            return True
        print("[-] [patch_touch] Warning: Target effort selector string not found in main.js.")
        return False

    new_content = content.replace(TARGET_STR, PATCHED_STR)
    with open(main_js_path, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"[+] [patch_touch] Successfully patched model thinking level selector in {main_js_path}!")
    return True

def patch_touch_ui(web_dir=None, core_binary_path=None):
    patched_any = False
    
    # Locate web_ui directory
    candidates = []
    if web_dir:
        candidates.append(web_dir)
    
    # Common locations relative to repo root
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(script_dir, ".."))
    candidates.append(os.path.join(repo_root, "web_ui"))
    candidates.append("web_ui")

    for d in candidates:
        js_path = os.path.join(d, "main.js") if os.path.isdir(d) else d
        if os.path.isfile(js_path):
            if patch_main_js(js_path):
                patched_any = True
            break

    return patched_any

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else None
    core_bin = sys.argv[2] if len(sys.argv) > 2 else None
    success = patch_touch_ui(web_dir=target, core_binary_path=core_bin)
    sys.exit(0 if success else 1)
