# 🚀 MobCode Release History

---

## 📦 Version 2.0.0 — MobCode v2
**Release Date:** November 2026  
**Tag:** `v2.0.0`

### 🌟 What's New in v2.0
- **Native Hardware-Accelerated Terminal Canvas:**
  - Integrated custom Android `TerminalView` directly into the Godot activity hierarchy.
  - Full ANSI/VT100 support with 256 colors, full scrollback buffer, and UTF-8 encoding.
- **SGR Mouse Tracking for OpenCode & Claude:**
  - Implemented DEC Private Modes 1000 and 1006 (SGR Mouse Mode).
  - TUI chat scrolling now dispatches mouse wheel escape sequences (`\033[<64...M`), allowing smooth chat log navigation without triggering readline command history.
- **Draggable Multi-Session Floating Overlay:**
  - System-level floating pill (`TYPE_APPLICATION_OVERLAY`) rendered over external applications.
  - Branded agent pills (`⚡ CODE`, `🪐 AGY`, `✳ CLAUDE`, `❯_ SH`) with live state colors.
  - Tap-to-resume direct navigation back to active sessions.
- **Intelligent Lifecycle & Approval Tracking:**
  - Real-time PTY stream heuristics detecting tool approvals (`Allow once`, `Always allow`, `Permission required`, `[y/N]`).
  - Dedicated Amber **"Needs Approval"** state alongside Working (Green), Completed (Blue), Stopped (Orange), and Error (Red).
- **Resilient Shell & Agent Fallback:**
  - Backend PTY server automatically falls back to native Termux `bash -l` if PRoot Ubuntu is unavailable, locked, or uninstalled.
  - Prevents session crashes and guarantees terminal access.
- **Termux ExtraKeys Accessory Toolbar:**
  - Two-row shortcut keyboard toolbar (`ESC`, `TAB`, `CTRL`, `ALT`, `|`, `-`, arrows) anchored directly above soft input keyboard with dynamic window insets.
- **Session Life Protection:**
  - Replaced abrupt auto-hiding with non-destructive completion messaging (`[Session finished - press Back to return]`).

---

## 📦 Version 1.0.0 — MobCode (Initial Release)
**Release Date:** November 2026  
**Tag:** `v1.0.0`

### 🌟 Key Features
- **Godot 4.x Mobile UI:**
  - Dark mobile theme with custom SVG brand cards for Antigravity, OpenCode, Claude Code, and Shell.
- **Session Management:**
  - Multi-session drawer and active sessions list.
  - Start, stop, and switch controls.
- **Termux Intent Bridge:**
  - Communication with Termux via `com.termux.RUN_COMMAND` intent system.
- **Background Daemon:**
  - State monitoring daemon (`mobcode_daemon.sh`) and status merger.
- **Voice Input:**
  - Hold-to-speak voice recognition button.
- **Shared Storage:**
  - Full read/write access to `/sdcard` workspace.
