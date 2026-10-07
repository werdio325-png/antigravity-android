# Customizations, Rules & Skills: Antigravity for Android

This guide explains how to customize the AI assistant's instructions, prompts, rules, and skills within **Antigravity for Android (AGY v2)**.

---

## 1. Customization Architecture

Antigravity employs a multi-tiered customization system:

| Type | Configuration File | Behavior | Use Case |
|---|---|---|---|
| **Always-On Rules** | `AGENTS.md`, `GEMINI.md` | Injected unconditionally into every conversation (up to 20,000 tokens budget) | Platform environment rules, coding constraints, tool execution guidelines |
| **On-Demand Skills** | `skills/<name>/SKILL.md` | Loaded progressively only when the user or agent invokes them | Procedural runbooks, domain recipes (Android ADB, Git workflows) |
| **Machine Config** | `~/.gemini/config/config.json` | Global settings | Sandbox policy, permissions, model overrides |

---

## 2. Default Android Rules (`AGENTS.md`)

To ensure the AI assistant is fully aware of its mobile environment from the first turn:
- Place `AGENTS.md` at `$HOME/.gemini/config/AGENTS.md` (or in project roots).
- It informs the agent:
  1. It is running on an **ARM64 Android 16 device** within package `com.agy`.
  2. Standard desktop **bwrap sandboxing is disabled** on Android; commands must be executed directly in the userspace shell.
  3. The local package manager is **`pkg`** (`pkg update`, `pkg install`).
  4. Elevated commands can be dispatched via **Shizuku / `rish`** (`rish -c "..."`) with `uid=2000(shell)` privileges.
  5. Shared storage resides at `/storage/emulated/0`.

---

## 3. Creating Custom Skills

A skill is a self-contained directory containing instructions and optional helper scripts:

```
skills/my-skill/
├── SKILL.md       # Frontmatter (name, description) + instructions
├── scripts/       # Optional helper scripts
└── references/    # Optional deep documentation
```

### Frontmatter Schema
```yaml
---
name: my-skill
description: Brief 1-2 sentence description explaining what this skill does and when the agent should activate it.
---
```

Skills bundled with AGY v2:
- `skills/android-environment/SKILL.md`: Recipes for `pkg`, Shizuku ADB, file permissions, and Android diagnostics.
- `skills/github-readme-architect/SKILL.md`: Design standards for creating product-focused GitHub READMEs.
