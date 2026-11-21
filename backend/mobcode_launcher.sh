#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# MobCode: Session Spawner, Key Dispatcher & Pause/Resume Manager
# ==============================================================================

ACTION="${1:-status}"
SESSION_ID="$2"
AGENT_TYPE="${3:-shell}"
PAYLOAD="$4"

STATUS_FILE="/sdcard/.mobcode_status.json"
PTY_STATUS="/sdcard/.mobcode_pty_sessions.json"
FOCUS_FILE="/sdcard/.mobcode_focused_session"
DAEMON_SCRIPT="$HOME/.mobcode/mobcode_daemon.sh"

find_pty_pid() {
    jq -r --arg id "$1" '(.sessions // [])[] | select(.id == $id) | .pid // empty' "$PTY_STATUS" 2>/dev/null | head -n 1
}

signal_pty() {
    local sig="$1"
    local sid="$2"
    local pid
    pid=$(find_pty_pid "$sid")
    if [ -n "$pid" ] && [ "$pid" != "0" ]; then
        kill -s "$sig" -- "-$pid" 2>/dev/null || kill -s "$sig" "$pid" 2>/dev/null || true
        return 0
    fi
    return 1
}

ensure_daemon() {
    if ! pgrep -f "mobcode_daemon.sh" > /dev/null 2>&1; then
        if [ -f "$DAEMON_SCRIPT" ]; then
            bash "$DAEMON_SCRIPT" > /dev/null 2>&1 &
        fi
    fi
}

termux-wake-lock 2>/dev/null || true

case "$ACTION" in
    "start")
        if [ -z "$SESSION_ID" ]; then
            echo "Error: Missing session ID"
            exit 1
        fi

        if tmux has-session -t "$SESSION_ID" 2>/dev/null || [ -n "$(find_pty_pid "$SESSION_ID")" ]; then
            echo "Session $SESSION_ID already running."
            echo "$SESSION_ID" > "$FOCUS_FILE"
            exit 0
        fi

        case "$AGENT_TYPE" in
            "agy")
                CMD="proot-distro login ubuntu -- bash -l -c 'cd /sdcard && agy'"
                ;;
            "opencode")
                CMD="proot-distro login ubuntu -- bash -l -c 'cd /sdcard && opencode'"
                ;;
            "claude")
                CMD="proot-distro login ubuntu -- bash -l -c 'cd /sdcard && claude'"
                ;;
            "shell"|*)
                CMD="proot-distro login ubuntu -- bash -l -c 'cd /sdcard && exec bash'"
                ;;
        esac

        tmux new-session -d -s "$SESSION_ID" "$CMD"
        echo "$SESSION_ID" > "$FOCUS_FILE"
        echo "Started session $SESSION_ID ($AGENT_TYPE)"
        ensure_daemon
        ;;

    "pause")
        if [ -n "$SESSION_ID" ]; then
            PANE_PID=$(tmux list-panes -t "$SESSION_ID" -F "#{pane_pid}" 2>/dev/null | head -n 1)
            if [ -n "$PANE_PID" ]; then
                pkill -STOP -P "$PANE_PID" 2>/dev/null || kill -STOP "$PANE_PID" 2>/dev/null || true
                echo "Paused session $SESSION_ID"
            elif signal_pty STOP "$SESSION_ID"; then
                echo "Paused session $SESSION_ID"
            fi
        fi
        ;;

    "resume")
        if [ -n "$SESSION_ID" ]; then
            PANE_PID=$(tmux list-panes -t "$SESSION_ID" -F "#{pane_pid}" 2>/dev/null | head -n 1)
            if [ -n "$PANE_PID" ]; then
                pkill -CONT -P "$PANE_PID" 2>/dev/null || kill -CONT "$PANE_PID" 2>/dev/null || true
                echo "Resumed session $SESSION_ID"
            elif signal_pty CONT "$SESSION_ID"; then
                echo "Resumed session $SESSION_ID"
            fi
        fi
        ;;

    "send_key")
        if [ -n "$SESSION_ID" ] && [ -n "$PAYLOAD" ]; then
            # Translate mobile shortcut key
            case "$PAYLOAD" in
                "ctrl_c")
                    tmux send-keys -t "$SESSION_ID" C-c
                    ;;
                "ctrl_z")
                    tmux send-keys -t "$SESSION_ID" C-z
                    ;;
                "esc")
                    tmux send-keys -t "$SESSION_ID" Escape
                    ;;
                "tab")
                    tmux send-keys -t "$SESSION_ID" Tab
                    ;;
                "enter")
                    tmux send-keys -t "$SESSION_ID" Enter
                    ;;
                "up")
                    tmux send-keys -t "$SESSION_ID" Up
                    ;;
                "down")
                    tmux send-keys -t "$SESSION_ID" Down
                    ;;
                *)
                    tmux send-keys -t "$SESSION_ID" "$PAYLOAD"
                    ;;
            esac
            echo "Sent key $PAYLOAD to $SESSION_ID"
        fi
        ;;

    "send_prompt")
        if [ -n "$SESSION_ID" ] && [ -n "$PAYLOAD" ]; then
            tmux send-keys -t "$SESSION_ID" "$PAYLOAD" Enter
            echo "Prompt delivered to $SESSION_ID"
        fi
        ;;

    "focus")
        if [ -n "$SESSION_ID" ]; then
            echo "$SESSION_ID" > "$FOCUS_FILE"
        fi
        ;;

    "stop")
        if [ -n "$SESSION_ID" ]; then
            tmux kill-session -t "$SESSION_ID" 2>/dev/null || true
            if signal_pty TERM "$SESSION_ID"; then
                sleep 0.2
                signal_pty KILL "$SESSION_ID" || true
            fi
            echo "Stopped session $SESSION_ID"
        fi
        ;;

    "stop_all")
        if [ -f "$PTY_STATUS" ]; then
            for PID in $(jq -r '(.sessions // [])[].pid // empty' "$PTY_STATUS" 2>/dev/null); do
                [ -n "$PID" ] && [ "$PID" != "0" ] && kill -s TERM -- "-$PID" 2>/dev/null || true
            done
            sleep 0.2
            for PID in $(jq -r '(.sessions // [])[].pid // empty' "$PTY_STATUS" 2>/dev/null); do
                [ -n "$PID" ] && [ "$PID" != "0" ] && kill -s KILL -- "-$PID" 2>/dev/null || true
            done
        fi
        tmux kill-server 2>/dev/null || true
        termux-wake-unlock 2>/dev/null || true
        echo '{"timestamp": '$(date +%s)', "daemon_online": true, "sessions": []}' > "$STATUS_FILE" 2>/dev/null || true
        rm -f "$FOCUS_FILE"
        echo "All sessions stopped."
        ;;

    "attach")
        if [ -n "$SESSION_ID" ]; then
            tmux attach-session -t "$SESSION_ID"
        fi
        ;;

    *)
        echo "Usage: mobcode_launcher.sh [start|stop|pause|resume|send_key|send_prompt|focus|attach|stop_all]"
        exit 1
        ;;
esac
