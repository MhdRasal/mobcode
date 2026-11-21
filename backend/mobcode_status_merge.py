#!/usr/bin/env python3
import json
import os
import sys
import time


def read_json(path):
    try:
        with open(path, "r") as f:
            return json.load(f)
    except Exception:
        return None


def main():
    if len(sys.argv) < 5:
        return 1
    out_path, tmux_path, pty_path, focus_path = sys.argv[1:5]
    now = int(time.time())
    sessions = {}

    tmux_data = read_json(tmux_path)
    if isinstance(tmux_data, dict):
        for session in tmux_data.get("sessions", []):
            if isinstance(session, dict) and session.get("id"):
                sessions[session["id"]] = session

    pty_data = read_json(pty_path)
    pty_focus = None
    pty_online = False
    if isinstance(pty_data, dict) and now - int(pty_data.get("timestamp", 0)) <= 5:
        pty_online = True
        pty_focus = pty_data.get("focused_session")
        for session in pty_data.get("sessions", []):
            if isinstance(session, dict) and session.get("id"):
                sessions[session["id"]] = session

    focus = None
    try:
        with open(focus_path, "r") as f:
            focus = f.read().strip()
    except Exception:
        pass

    if focus not in sessions:
        focus = pty_focus if pty_focus in sessions else None
    if focus is None and sessions:
        focus = next(iter(sessions))

    data = {
        "timestamp": now,
        "daemon_online": True,
        "merged": pty_online,
        "pty_online": pty_online,
        "focused_session": focus,
        "sessions": list(sessions.values())
    }

    tmp_path = out_path + ".tmp"
    try:
        with open(tmp_path, "w") as f:
            json.dump(data, f)
        os.replace(tmp_path, out_path)
    except Exception:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
