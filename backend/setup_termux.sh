#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# MobCode: Termux Initial Setup & Daemon Starter
# Run this ONCE inside Termux: bash /sdcard/godot/mobcode/backend/setup_termux.sh
# ==============================================================================

set -e

echo "[MobCode] Initializing environment setup..."

# 1. Enable external app intent access for Termux RUN_COMMAND
mkdir -p "$HOME/.termux"
TERMUX_PROP="$HOME/.termux/termux.properties"
if ! grep -q "allow-external-apps" "$TERMUX_PROP" 2>/dev/null; then
    echo "allow-external-apps = true" >> "$TERMUX_PROP"
else
    sed -i 's/^#*\s*allow-external-apps\s*=.*/allow-external-apps = true/' "$TERMUX_PROP"
fi
termux-reload-settings 2>/dev/null || true

# 2. Acquire initial wake-lock
termux-wake-lock 2>/dev/null || true
echo "[MobCode] Acquired CPU wake-lock"

# 3. Update repositories & install core packages
echo "[MobCode] Installing required packages (tmux, proot-distro, jq)..."
pkg update -y || apt-get update -y
pkg install -y tmux proot-distro jq curl coreutils || apt-get install -y tmux proot-distro jq curl coreutils

# 4. Check & Install PRoot Ubuntu distro
if ! proot-distro list | grep -q "ubuntu.*installed"; then
    echo "[MobCode] Installing Ubuntu PRoot container (this may take a few minutes)..."
    proot-distro install ubuntu
else
    echo "[MobCode] Ubuntu PRoot container already installed."
fi

# 5. Copy mobcode scripts to Termux home for execution
mkdir -p "$HOME/.mobcode"
SOURCE_DIR="/sdcard/godot/mobcode/backend"
if [ ! -d "$SOURCE_DIR" ]; then
    SOURCE_DIR="/storage/emulated/0/godot/mobcode/backend"
fi

cp "$SOURCE_DIR/mobcode_launcher.sh" "$HOME/.mobcode/" 2>/dev/null || true
cp "$SOURCE_DIR/mobcode_daemon.sh" "$HOME/.mobcode/" 2>/dev/null || true
cp "$SOURCE_DIR/mobcode_pty_server.py" "$HOME/.mobcode/" 2>/dev/null || true
cp "$SOURCE_DIR/mobcode_status_merge.py" "$HOME/.mobcode/" 2>/dev/null || true
chmod -R 777 "$HOME/.mobcode" 2>/dev/null || true
mkdir -p /sdcard/.mobcode_cmd_queue 2>/dev/null || true

# 6. Install auto-start hook in .bashrc
BASHRC="$HOME/.bashrc"
MARKER="# MOBCODE_DAEMON_AUTO"

if ! grep -q "$MARKER" "$BASHRC" 2>/dev/null; then
    cat >> "$BASHRC" <<'AUTOSTART'

# MOBCODE_DAEMON_AUTO
# Auto-start MobCode daemon when Termux opens (if trigger file exists or always)
_mobcode_autostart() {
    local DAEMON="$HOME/.mobcode/mobcode_daemon.sh"
    local DAEMON_ALT="/sdcard/godot/mobcode/backend/mobcode_daemon.sh"
    # Pick whichever daemon script exists
    if [ ! -f "$DAEMON" ]; then
        DAEMON="$DAEMON_ALT"
    fi
    if [ -f "$DAEMON" ] && ! pgrep -f 'mobcode_daemon.sh' > /dev/null 2>&1; then
        nohup bash "$DAEMON" > /dev/null 2>&1 &
        echo "[MobCode] Daemon started (pid $!)"
    fi
}
_mobcode_autostart
AUTOSTART
    echo "[MobCode] Auto-start hook installed in .bashrc"
else
    echo "[MobCode] Auto-start hook already in .bashrc"
fi

# 7. Start the daemon right now
echo "[MobCode] Starting MobCode Daemon..."
pkill -f "mobcode_daemon.sh" 2>/dev/null || true
sleep 0.5
nohup bash "$HOME/.mobcode/mobcode_daemon.sh" > /dev/null 2>&1 &

echo ""
echo "=================================================="
echo "✅ MobCode Engine is now ONLINE!"
echo "   Daemon PID: $!"
echo "   Switch back to your MobCode app and enjoy!"
echo ""
echo "   The daemon will auto-start every time you open Termux."
echo "=================================================="
