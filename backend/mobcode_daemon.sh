#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# MobCode: State Monitoring Daemon & IPC Queue Processor
# ==============================================================================

STATUS_FILE="/sdcard/.mobcode_status.json"
TEMP_FILE="/sdcard/.mobcode_status.tmp"
PTY_STATUS="/sdcard/.mobcode_pty_sessions.json"
TMUX_STATUS="/sdcard/.mobcode_tmux_status.json"
MERGER="$HOME/.mobcode/mobcode_status_merge.py"
MERGER_ALT="/sdcard/godot/mobcode/backend/mobcode_status_merge.py"
CMD_FILE="/sdcard/.mobcode_cmd"
CMD_DIR="/sdcard/.mobcode_cmd_queue"
mkdir -p "$CMD_DIR" 2>/dev/null
LOG_FILE="/sdcard/.mobcode_log"
PANE_LOG="/sdcard/.mobcode_pane.log"
FOCUS_FILE="/sdcard/.mobcode_focused_session"
LAUNCHER="$HOME/.mobcode/mobcode_launcher.sh"
LAUNCHER_ALT="/sdcard/godot/mobcode/backend/mobcode_launcher.sh"
# Fallback to sdcard if home copy doesn't exist
if [ ! -f "$LAUNCHER" ] && [ -f "$LAUNCHER_ALT" ]; then
    LAUNCHER="$LAUNCHER_ALT"
fi
if [ ! -f "$MERGER" ] && [ -f "$MERGER_ALT" ]; then
    MERGER="$MERGER_ALT"
fi

log_event() {
    echo "[$(date '+%H:%M:%S')] $1" >> "$LOG_FILE"
}

PIDFILE="/data/data/com.termux/files/usr/tmp/mobcode_daemon.pid"
mkdir -p "$(dirname "$PIDFILE")"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    exit 0
fi
echo "$$" > "$PIDFILE"

PTY_SERVER="$HOME/.mobcode/mobcode_pty_server.py"
PTY_SERVER_ALT="/sdcard/godot/mobcode/backend/mobcode_pty_server.py"
[ ! -f "$PTY_SERVER" ] && PTY_SERVER="$PTY_SERVER_ALT"

ensure_pty_server() {
    if ! pgrep -f "mobcode_pty_server.py" > /dev/null 2>&1; then
        if [ -f "$PTY_SERVER" ]; then
            nohup python3 "$PTY_SERVER" > /sdcard/.mobcode_pty.log 2>&1 &
            log_event "Started PTY Multiplexer Server (PID $!)"
        fi
    fi
}
ensure_pty_server

cleanup() {
    rm -f "$PIDFILE" "$TEMP_FILE"
    exit 0
}
trap cleanup SIGINT SIGTERM EXIT

TICK=0
while true; do
    # 1. Process command spool queue (guarantees FIFO order without overwrites)
    if [ -d "$CMD_DIR" ]; then
        for CMD_F in $(ls -1 "$CMD_DIR"/*.cmd 2>/dev/null | sort); do
            [ -f "$CMD_F" ] || continue
            RAW_CMD=$(cat "$CMD_F" 2>/dev/null)
            rm -f "$CMD_F"

            if [ -n "$RAW_CMD" ]; then
                log_event "Queue CMD: $RAW_CMD"
                IFS=':' read -r ACTION S_ID A_TYPE PROMPT <<< "$RAW_CMD"
                if [ -f "$LAUNCHER" ]; then
                    bash "$LAUNCHER" "$ACTION" "$S_ID" "$A_TYPE" "$PROMPT" >> "$LOG_FILE" 2>&1
                fi
            fi
        done
    fi

    # Fallback: single command file
    if [ -f "$CMD_FILE" ]; then
        RAW_CMD=$(cat "$CMD_FILE" 2>/dev/null)
        rm -f "$CMD_FILE"

        if [ -n "$RAW_CMD" ]; then
            log_event "Direct CMD: $RAW_CMD"
            IFS=':' read -r ACTION S_ID A_TYPE PROMPT <<< "$RAW_CMD"
            if [ -f "$LAUNCHER" ]; then
                bash "$LAUNCHER" "$ACTION" "$S_ID" "$A_TYPE" "$PROMPT" >> "$LOG_FILE" 2>&1
            fi
        fi
    fi

    # 2. Fast live pane capture of focused session (runs every 100ms)
    FOCUSED_SESSION=""
    [ -f "$FOCUS_FILE" ] && FOCUSED_SESSION=$(cat "$FOCUS_FILE" 2>/dev/null)
    # If focused session does not exist, fall back to first active session
    if [ -n "$FOCUSED_SESSION" ] && ! tmux has-session -t "$FOCUSED_SESSION" 2>/dev/null; then
        FOCUSED_SESSION=$(tmux list-sessions -F "#{session_name}" 2>/dev/null | head -n 1)
        [ -n "$FOCUSED_SESSION" ] && echo "$FOCUSED_SESSION" > "$FOCUS_FILE" 2>/dev/null
    fi

    if [ -n "$FOCUSED_SESSION" ] && tmux has-session -t "$FOCUSED_SESSION" 2>/dev/null; then
        tmux capture-pane -pt "$FOCUSED_SESSION" -S -40 > "$PANE_LOG" 2>/dev/null || true
    fi

    # 3. Status JSON check (runs every 10 ticks = ~1 second)
    TICK=$((TICK + 1))
    if [ $TICK -ge 10 ]; then
        TICK=0
        ensure_pty_server
        SESSIONS_JSON="["
        FIRST=1

        while IFS= read -r line; do
            [ -z "$line" ] && continue

            SESSION_NAME=$(echo "$line" | cut -d: -f1)
            PANE_PID=$(echo "$line" | cut -d: -f3)
            PANE_CMD=$(echo "$line" | cut -d: -f4)

            [ -z "$FOCUSED_SESSION" ] && FOCUSED_SESSION="$SESSION_NAME"

            AGENT_TYPE="shell"
            if [[ "$SESSION_NAME" =~ ^agy ]]; then
                AGENT_TYPE="agy"
            elif [[ "$SESSION_NAME" =~ ^opencode ]]; then
                AGENT_TYPE="opencode"
            elif [[ "$SESSION_NAME" =~ ^claude ]]; then
                AGENT_TYPE="claude"
            fi

            STATUS="idle"
            if [ -n "$PANE_PID" ]; then
                P_STATE=$(ps -o state= -p "$PANE_PID" 2>/dev/null)
                if [[ "$P_STATE" =~ T ]]; then
                    STATUS="paused"
                else
                    CHILD_PIDS=$(pgrep -P "$PANE_PID" 2>/dev/null)
                    PANE_TAIL=$(tmux capture-pane -pt "$SESSION_NAME" -S -15 2>/dev/null)

                    if echo "$PANE_TAIL" | grep -Eiq '\[\s*[yY]\s*/\s*[nN]\s*\]|\b(Approve|Proceed|Confirm|Allow|Authorize|Permission)\b.*(\?|:)|Allow (once|always|tool)|Password:|❯\s*(\[[0-9]\]\s*)?(Allow|Approve|Yes)'; then
                        STATUS="needs_attention"
                    elif [ -n "$CHILD_PIDS" ]; then
                        STATUS="running"
                    elif echo "$PANE_TAIL" | grep -Eq '([$#>❯➜%]|agy>|claude>|opencode>)\s*$'; then
                        STATUS="idle"
                    else
                        STATUS="idle"
                    fi
                fi
            fi

            ITEM=$(cat <<EOF
{
    "id": "$SESSION_NAME",
    "type": "$AGENT_TYPE",
    "status": "$STATUS",
    "pid": "${PANE_PID:-0}"
}
EOF
)
            if [ $FIRST -eq 1 ]; then
                SESSIONS_JSON="${SESSIONS_JSON}${ITEM}"
                FIRST=0
            else
                SESSIONS_JSON="${SESSIONS_JSON},${ITEM}"
            fi
        done < <(tmux list-panes -a -F "#{session_name}:#{window_active}:#{pane_pid}:#{pane_current_command}" 2>/dev/null)

        SESSIONS_JSON="${SESSIONS_JSON}]"

        TMUX_JSON=$(cat <<EOF
{
    "timestamp": $(date +%s),
    "daemon_online": true,
    "focused_session": "$FOCUSED_SESSION",
    "sessions": $SESSIONS_JSON
}
EOF
)
        echo "$TMUX_JSON" > "$TEMP_FILE" 2>/dev/null && mv "$TEMP_FILE" "$TMUX_STATUS" 2>/dev/null

        if [ -f "$MERGER" ]; then
            python3 "$MERGER" "$STATUS_FILE" "$TMUX_STATUS" "$PTY_STATUS" "$FOCUS_FILE" 2>/dev/null || rm -f "$STATUS_FILE"
        fi
        if [ ! -s "$STATUS_FILE" ] || [ "$(find "$STATUS_FILE" -mmin +1 2>/dev/null)" != "" ]; then
            if [ -s "$PTY_STATUS" ]; then
                cp "$PTY_STATUS" "$TEMP_FILE" 2>/dev/null && mv "$TEMP_FILE" "$STATUS_FILE" 2>/dev/null
            else
                echo "$TMUX_JSON" > "$TEMP_FILE" 2>/dev/null && mv "$TEMP_FILE" "$STATUS_FILE" 2>/dev/null
            fi
        fi

        if ! tmux list-sessions > /dev/null 2>&1; then
            echo "No tmux sessions running." > "$PANE_LOG" 2>/dev/null
        fi
    fi

    sleep 0.1
done
