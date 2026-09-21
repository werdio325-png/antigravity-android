---
name: apk-and-smali-patching
description: >-
  Techniques and workflows for disassembling, modifying, and rebuilding Android APKs,
  Dalvik/ART Smali bytecode, AndroidManifest, and DEX classes.
---

# Android APK & Smali Reverse Engineering & Patching Guide

This skill guides the agent through static reverse engineering and modification of Android applications using `apktool`, `dex2jar`, and Dalvik Smali patching.

---

## 1. Toolchain Quick Reference

Tool | Purpose | Command
:--- | :--- | :---
**Apktool** | Decompile APK resources and code to Smali | `apktool d -r target.apk -o out_dir`
**Apktool Rebuild** | Assemble Smali back into an unsigned APK | `apktool b out_dir -o patched_unsigned.apk`
**Dex2Jar** | Convert DEX to JAR for Java decompilers | `d2j-dex2jar classes.dex -o classes.jar`
**Zipalign** | 4-byte align uncompressed data in APK | `zipalign -p -f 4 in.apk out.apk`
**Apksigner** | Sign APK with debug/release keystore | `apksigner sign --ks debug.keystore --ks-pass pass:android out.apk`

---

## 2. Common Smali Patching Patterns

### A. Force Boolean Method Return (e.g. License Check / Root Check)

Original:
```smali
.method public isProUser()Z
    .registers 2
    invoke-virtual {p0}, Lcom/app/Billing;->checkLicense()Z
    move-result v0
    return v0
.end method
```

Patched to always return `true`:
```smali
.method public isProUser()Z
    .registers 2
    const/4 v0, 0x1
    return v0
.end method
```

Patched to always return `false` (e.g. `isDeviceRooted()`):
```smali
.method public isDeviceRooted()Z
    .registers 2
    const/4 v0, 0x0
    return v0
.end method
```

### B. Voiding out Security or Anti-Tamper Checks

Original:
```smali
invoke-virtual {p0}, Lcom/app/SecurityManager;->verifySignature()V
```

Patched:
Replace with `nop`:
```smali
nop
```

### C. Inserting Android Log Messages in Smali
To inspect runtime values without a debugger:
```smali
const-string v0, "ANTIGRAVITY_DEBUG"
const-string v1, "Secret method reached!"
invoke-static {v0, v1}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
```

---

## 3. End-to-End Repackaging Workflow

```bash
# 1. Decompile APK
apktool d app.apk -o app_src

# 2. Modify Smali files or AndroidManifest.xml inside app_src/
# (e.g. android:debuggable="true", android:networkSecurityConfig, Smali logic)

# 3. Rebuild APK
apktool b app_src -o app_patched_unaligned.apk

# 4. 4-Byte Alignment (Mandatory for Android 7.0+)
zipalign -p -f 4 app_patched_unaligned.apk app_patched_aligned.apk

# 5. Sign the APK
apksigner sign --ks tools/debug.keystore --ks-pass pass:android --key-pass pass:android app_patched_aligned.apk

# 6. Verify signature
apksigner verify app_patched_aligned.apk
```
