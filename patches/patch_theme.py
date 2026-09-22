#!/usr/bin/env python3
"""
Antigravity Theme Mode & Dynamic System Theme Patcher

Fixes theme synchronization between Android native system and Web UI:
1. Replaces sU() in main.js to query window.AntigravityNative.isSystemDark() instead
   of being poisoned by the language server binary default theme ("dark").
2. Exposes window.__refreshSystemTheme in main.js so Android can trigger an immediate
   live re-render of the theme when system theme changes in Android Quick Settings.
3. Hooks pushUpdate for themeMode so Android native gets notified immediately when
   the user selects System (1), Light (2), or Dark (3).
"""

import os
import sys

TARGET_SU = 'function sU(){var a=Wi();return a!==void 0?a==="dark":typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}'
PATCHED_SU = 'function sU(){if(window.AntigravityNative&&typeof window.AntigravityNative.isSystemDark==="function"){return window.AntigravityNative.isSystemDark()}return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}'

TARGET_REFRESH = '(c=()=>{g.getState()===1&&m(1)},Ti.onDidChange(c),window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",c))'
PATCHED_REFRESH = '(c=()=>{g.getState()===1&&m(1)},window.__refreshSystemTheme=c,Ti.onDidChange(c),window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",c))'

TARGET_PUSH = 'pushUpdate:n=>{switch(n){case 2:var p=2;break;case 3:p=1;break;case 1:p=3;break;default:p=1}W7b(a,p);m(n)}'
PATCHED_PUSH = 'pushUpdate:n=>{try{window.AntigravityNative&&window.AntigravityNative.onThemeModeSelected&&window.AntigravityNative.onThemeModeSelected(n===2?"light":n===3?"dark":"system")}catch(_){}switch(n){case 2:var p=2;break;case 3:p=1;break;case 1:p=3;break;default:p=1}W7b(a,p);m(n)}'

def patch_theme(web_dir):
    main_js_path = os.path.join(web_dir, "main.js")
    if not os.path.isfile(main_js_path):
        print(f"[-] [patch_theme] main.js not found at: {main_js_path}")
        return False

    with open(main_js_path, "r", encoding="utf-8") as f:
        content = f.read()

    changed = False

    if TARGET_SU in content:
        content = content.replace(TARGET_SU, PATCHED_SU)
        changed = True
        print("[+] [patch_theme] Patched sU() to query AntigravityNative.isSystemDark().")
    elif PATCHED_SU in content:
        print("[*] [patch_theme] sU() already patched.")
    else:
        print("[-] [patch_theme] Warning: TARGET_SU not found.")

    if TARGET_REFRESH in content:
        content = content.replace(TARGET_REFRESH, PATCHED_REFRESH)
        changed = True
        print("[+] [patch_theme] Patched window.__refreshSystemTheme handler.")
    elif PATCHED_REFRESH in content:
        print("[*] [patch_theme] window.__refreshSystemTheme already patched.")
    else:
        print("[-] [patch_theme] Warning: TARGET_REFRESH not found.")

    if TARGET_PUSH in content:
        content = content.replace(TARGET_PUSH, PATCHED_PUSH)
        changed = True
        print("[+] [patch_theme] Patched onThemeModeSelected hook in pushUpdate.")
    elif PATCHED_PUSH in content:
        print("[*] [patch_theme] onThemeModeSelected hook already patched.")
    else:
        print("[-] [patch_theme] Warning: TARGET_PUSH not found.")

    if changed:
        with open(main_js_path, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"[+] [patch_theme] Successfully saved patched {main_js_path}!")

    return True

if __name__ == "__main__":
    target_dir = sys.argv[1] if len(sys.argv) > 1 else "web_ui"
    patch_theme(target_dir)
