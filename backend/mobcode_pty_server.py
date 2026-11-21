#!/usr/bin/env python3
# ==============================================================================
# MobCode: High-Performance PTY Multiplexer Server
# Bridges genuine Linux PTYs to Android TerminalSession with microsecond latency
# ==============================================================================

import os
import pty
import termios
import struct
import fcntl
import socket
import select
import signal
import sys
import json
import time
import re

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 8765
SESSIONS_FILE = "/sdcard/.mobcode_pty_sessions.json"
STATUS_FILE = "/sdcard/.mobcode_status.json"

ANSI_REGEX = re.compile(
    r'\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1b\\)|[@-Z\\-_])'
)

RE_SPINNER = re.compile(
    r'[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]'
    r'|\b(?:Thinking|Generating|Working|Searching|Analyzing|Fetching|'
    r'Running command|Processing|Installing)\b(?:[.…]{2,3})?'
    r'|\b(?:esc|ctrl\+c)\s+to\s+(?:interrupt|cancel)\b'
    r'|\b\d{1,3}%\b',
    re.IGNORECASE,
)

RE_SHELL_PROMPT = re.compile(
    r'(?:[a-zA-Z0-9_\-\.]+@[a-zA-Z0-9_\-\.]+:[^\n#$]*[#$]'
    r'|bash-[0-9\.]+[#$]|[\$#>❯➜%])\s*$'
)

RE_NEEDS_ATTENTION = re.compile(
    r'\[\s*[yY]\s*/\s*[nN]\s*\]'
    r'|\(\s*[yY]\s*/\s*[nN]\s*\)'
    r'|\[\s*yes\s*/\s*no\s*\]'
    r'|\b(?:do you want to|would you like to|are you sure)\b[^\n?]*\?'
    r'|\b(?:approve|proceed|confirm|allow|grant|trust|authorize)\b[^\n?]*(?:\?|:)'
    r'|\b(?:allow once|always allow|allow this|reject|deny)\b'
    r'|\ballow (?:tool|execution|access|permission|once|always)\b'
    r'|\bpermission\s*(?:required|request|needed)\b'
    r'|\brequires?\s*permission\b'
    r'|\bpress (?:enter|any key) to continue\b'
    r'|\btype .*(?:yes|no).* to confirm\b'
    r'|\[sudo\] password for'
    r'|\bpassword\s*:\s*$'
    r'|\bchoice\s*\[[0-9\-]+\]:'
    r'|\bselect an option:'
    r'|\bwaiting for (?:approval|input|confirmation|user)\b'
    r'|\b(?:login|sign in|authentication|verification code)\b[^\n?]*\?'
    r'|\b(?:fatal|critical|error|permission denied|access denied)\b\s*:'
    r'|❯\s*(?:\[?[0-9]\]?\s*)?(?:allow|approve|yes|accept|run|proceed)\b'
    r'|\?\s+(?:allow|approve|run|execute|proceed|authorize)\b',
    re.IGNORECASE,
)

RE_ERROR_LINE = re.compile(
    r'^\s*(?:fatal|critical|error|failed|exception|traceback)\b',
    re.IGNORECASE,
)

AGENT_TYPES = {"agy", "opencode", "claude"}

OUTPUT_ACTIVE_SECONDS = 1.6
AGENT_PROMPT_QUIET_SECONDS = 1.2
COMPLETED_HOLD_SECONDS = 45.0
EXITED_GRACE_SECONDS = 90.0

# Active sessions: session_id -> { 'master_fd': int, 'pid': int, 'agent_type': str, 'socket': socket|None, 'history': bytes }
sessions = {}
focused_session = None

def set_winsize(fd, rows, cols):
    """Set window size for PTY (emits SIGWINCH to subprocess)."""
    try:
        ws = struct.pack("HHHH", int(rows), int(cols), 0, 0)
        fcntl.ioctl(fd, termios.TIOCSWINSZ, ws)
    except Exception as e:
        sys.stderr.write(f"[PTY] Error setting winsize: {e}\n")

def spawn_session(session_id, agent_type, rows=24, cols=80):
    """Spawns a new process inside a real Linux pseudo-terminal (PTY) with full job control."""
    if session_id in sessions and sessions[session_id].get("master_fd"):
        s = sessions[session_id]
        set_winsize(s["master_fd"], rows, cols)
        return s

    # Command to run based on agent type (with resilient fallback to native Termux bash)
    if agent_type in AGENT_TYPES:
        cmd = (
            f"if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true 2>/dev/null; then "
            f"proot-distro login ubuntu -- bash -l -c 'cd /sdcard 2>/dev/null || cd ~; {agent_type}'; "
            f"elif command -v {agent_type} >/dev/null 2>&1; then "
            f"cd /sdcard 2>/dev/null || cd ~; {agent_type}; "
            f"else "
            f"echo '[MobCode] Agent {agent_type} not found in proot or native PATH. Launching shell:'; "
            f"cd /sdcard 2>/dev/null || cd ~; exec bash -l; fi"
        )
    else:
        cmd = (
            "if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true 2>/dev/null; then "
            "proot-distro login ubuntu -- bash -l -c 'cd /sdcard 2>/dev/null || cd ~; exec bash -l'; "
            "else "
            "cd /sdcard 2>/dev/null || cd ~; exec bash -l; fi"
        )

    master_fd, slave_fd = pty.openpty()
    set_winsize(master_fd, rows, cols)

    pid = os.fork()
    if pid == 0:
        # Child process: acquire controlling terminal for complete job control
        os.close(master_fd)
        os.setsid()
        try:
            # Set slave_fd as the controlling terminal (TIOCSCTTY)
            fcntl.ioctl(slave_fd, termios.TIOCSCTTY, 0)
        except Exception:
            pass

        os.dup2(slave_fd, 0)
        os.dup2(slave_fd, 1)
        os.dup2(slave_fd, 2)
        if slave_fd > 2:
            os.close(slave_fd)

        # Environment
        os.environ["TERM"] = "xterm-256color"
        os.environ["COLORTERM"] = "truecolor"
        os.environ["LANG"] = "en_US.UTF-8"

        # Execute shell
        os.execlp("sh", "sh", "-c", cmd)
        sys.exit(1)

    # Parent process
    os.close(slave_fd)
    # Set non-blocking on master_fd
    fl = fcntl.fcntl(master_fd, fcntl.F_GETFL)
    fcntl.fcntl(master_fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)

    sessions[session_id] = {
        "master_fd": master_fd,
        "pid": pid,
        "agent_type": agent_type,
        "socket": None,
        "rows": rows,
        "cols": cols,
        "history": b"",
        "last_output_time": time.time(),
        "last_input_time": 0.0,
        "spawn_time": time.time(),
        "had_activity": False,
        "is_alive": True,
        "exit_code": 0,
        "exit_time": 0.0,
        "attention_since": 0.0
    }
    global focused_session
    focused_session = session_id
    _save_status()
    sys.stdout.write(f"[PTY] Spawned session {session_id} ({agent_type}) PID={pid}\n")
    sys.stdout.flush()
    return sessions[session_id]

def _check_process_alive(session):
    pid = session.get("pid")
    if not pid:
        return False
    try:
        wpid, status = os.waitpid(pid, os.WNOHANG)
        if wpid == pid:
            exit_code = os.WEXITSTATUS(status) if os.WIFEXITED(status) else (128 + os.WTERMSIG(status))
            session["exit_code"] = exit_code
            session["is_alive"] = False
            session["exit_time"] = time.time()
            return False
    except ChildProcessError:
        pass
    except Exception:
        pass

    if not os.path.exists(f"/proc/{pid}"):
        session["is_alive"] = False
        if not session.get("exit_time"):
            session["exit_time"] = time.time()
        return False

    session["is_alive"] = True
    return True

def _is_paused(pid):
    try:
        with open(f"/proc/{pid}/stat", "r") as f:
            state = f.readline().rsplit(")", 1)[1].split()[0]
        return state == "T"
    except Exception:
        return False

def _tty_foreground_busy(session):
    pid = session.get("pid")
    if not pid:
        return False
    try:
        with open(f"/proc/{pid}/stat", "r") as f:
            fields = f.readline().rsplit(")", 1)[1].split()
        pgrp = int(fields[2])
        tpgid = int(fields[5])
        if tpgid <= 0 or pgrp == tpgid:
            return False
        try:
            with open(f"/proc/{tpgid}/comm", "r") as cf:
                comm = cf.read().strip()
        except Exception:
            comm = ""
        if comm in ("proot", "proot-distro", "sh", "bash", "dash"):
            return False
        return True
    except Exception:
        return False


def _is_shell_command_running(session):
    """Returns True if a foreground child command (e.g. ls, git, sleep, python) is running."""
    master_fd = session.get("master_fd")
    shell_pid = session.get("pid")
    if not master_fd or not shell_pid:
        return False
    try:
        fg_pgrp = os.tcgetpgrp(master_fd)
        shell_pgrp = os.getpgid(shell_pid)
        if fg_pgrp > 0 and shell_pgrp > 0 and fg_pgrp != shell_pgrp:
            return True
    except Exception:
        pass

    try:
        with open(f"/proc/{shell_pid}/task/{shell_pid}/children", "r") as f:
            children = f.read().strip()
            if children:
                return True
    except Exception:
        pass

    return False

def _visible_tail(session, max_bytes=8192):
    """Return ANSI-free recent output and a small line window."""
    try:
        raw_tail = session.get("history", b"")[-max_bytes:].decode("utf-8", "replace")
    except Exception:
        raw_tail = ""
    text = ANSI_REGEX.sub("", raw_tail).replace("\r", "\n")
    lines = [line.rstrip() for line in text.splitlines() if line.strip()]
    return text, lines


def _tail_has_attention(lines, text=""):
    sample = (text[-4096:] if text else "") + "\n" + "\n".join(lines[-30:])
    return bool(RE_NEEDS_ATTENTION.search(sample))


def _tail_has_error_line(lines):
    return any(RE_ERROR_LINE.search(line) for line in lines[-3:])


def _agent_prompt_visible(lines):
    for raw in reversed(lines[-12:]):
        cleaned = raw.strip().strip("│|╭╮╰╯─ ")
        if re.match(r'^(?:agy|claude|opencode)\s*[>❯›]', cleaned, re.IGNORECASE):
            return True
        if re.match(r'^[>❯›](?:\s|$)', cleaned) and len(cleaned) < 160:
            return True
        if re.search(r'\b(?:for shortcuts|shift\+tab to cycle)\b', cleaned, re.IGNORECASE):
            return True
    return False


def _shell_prompt_visible(lines):
    if not lines:
        return False
    last = lines[-1].strip()
    return len(last) < 300 and bool(RE_SHELL_PROMPT.search(last))


def _busy_indicator_visible(lines):
    return bool(RE_SPINNER.search("\n".join(lines[-8:])))


def _detect_session_status(session):
    now = time.time()
    exit_code = int(session.get("exit_code", 0) or 0)

    if not session.get("is_alive", True):
        return "error" if exit_code != 0 else "completed"

    if not _check_process_alive(session):
        exit_code = int(session.get("exit_code", 0) or 0)
        return "error" if exit_code != 0 else "completed"

    pid = session.get("pid")
    if pid and _is_paused(pid):
        return "paused"

    _text, lines = _visible_tail(session)
    agent_type = session.get("agent_type", "shell")
    had_activity = bool(session.get("had_activity", False))
    last_out = float(session.get("last_output_time", 0.0) or 0.0)
    last_input = float(session.get("last_input_time", 0.0) or 0.0)
    spawn_time = float(session.get("spawn_time", now) or now)
    quiet = max(0.0, now - last_out)

    if not had_activity and not last_input:
        return "pending" if now - spawn_time < 10.0 else "idle"

    attention_visible = _tail_has_attention(lines, _text)
    attention_since = float(session.get("attention_since", 0.0) or 0.0)
    if attention_visible and last_out >= last_input:
        if attention_since == 0.0:
            attention_since = now
            session["attention_since"] = attention_since
        return "needs_attention"
    elif not attention_visible:
        session["attention_since"] = 0.0

    if last_input > last_out and now - last_input < 8.0:
        return "running"

    if agent_type == "shell":
        if _tty_foreground_busy(session):
            return "running"
        if _shell_prompt_visible(lines):
            if had_activity and quiet < COMPLETED_HOLD_SECONDS:
                return "completed"
            return "idle"
        if _is_shell_command_running(session):
            return "running"
        if quiet < OUTPUT_ACTIVE_SECONDS:
            return "running"
        if had_activity and quiet < COMPLETED_HOLD_SECONDS:
            return "completed"
        return "idle"

    if _busy_indicator_visible(lines) and quiet < 8.0:
        return "running"

    if _agent_prompt_visible(lines):
        if had_activity and AGENT_PROMPT_QUIET_SECONDS <= quiet < COMPLETED_HOLD_SECONDS:
            return "completed"
        if quiet < AGENT_PROMPT_QUIET_SECONDS:
            return "running"
        return "idle"

    if quiet < OUTPUT_ACTIVE_SECONDS:
        return "running"

    # No prompt row detected. Recent output was already handled above. Do
    # not claim Completed merely because the agent went quiet; it may be
    # waiting on a tool/network request without drawing a prompt.
    if had_activity and quiet > COMPLETED_HOLD_SECONDS:
        return "idle"
    return "running" if had_activity and quiet < 8.0 else "idle"

def _handle_session_eof(session):
    try:
        mfd = session.get("master_fd")
        if mfd is not None:
            try:
                os.close(mfd)
            except Exception:
                pass
            session["master_fd"] = None
        _check_process_alive(session)
        sock = session.get("socket")
        if sock:
            try:
                sock.close()
            except Exception:
                pass
            session["socket"] = None
        _save_status()
    except Exception:
        pass

def _write_json(path, data):
    try:
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.replace(tmp, path)
    except Exception:
        pass

def _save_status():
    """Updates status file for dashboard and the Android overlay."""
    global focused_session
    active = []
    now = time.time()
    expired = []
    for s_id, s in sessions.items():
        pid = s.get("pid")
        exit_time = float(s.get("exit_time", 0.0) or 0.0)
        if not pid and not exit_time:
            continue
        if exit_time and now - exit_time > EXITED_GRACE_SECONDS:
            expired.append(s_id)
            continue
        active.append({
            "id": s_id,
            "type": s["agent_type"],
            "status": _detect_session_status(s),
            "pid": pid or 0,
            "exit_code": int(s.get("exit_code", 0) or 0),
            "exited": not bool(pid) or not bool(s.get("is_alive", True))
        })
    for s_id in expired:
        sessions.pop(s_id, None)

    live_ids = [s["id"] for s in active if not s.get("exited")]
    if focused_session not in live_ids:
        focused_session = live_ids[0] if live_ids else None
    data = {
        "timestamp": int(now),
        "daemon_online": True,
        "focused_session": focused_session,
        "sessions": active
    }
    _write_json(SESSIONS_FILE, data)
    _write_json(STATUS_FILE, data)

def main():
    global focused_session
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind((SERVER_HOST, SERVER_PORT))
    server_sock.listen(10)
    server_sock.setblocking(False)

    sys.stdout.write(f"[PTY] Server listening on {SERVER_HOST}:{SERVER_PORT}\n")
    sys.stdout.flush()

    # Map: socket_fd -> { 'sock': socket, 'session_id': str, 'buffer': bytes }
    clients = {}
    last_status_write = 0

    while True:
        try:
            now = time.time()
            if now - last_status_write >= 1:
                _save_status()
                last_status_write = now
            # Check dead children
            for s_id, s in list(sessions.items()):
                pid = s.get("pid")
                if pid:
                    try:
                        res_pid, status = os.waitpid(pid, os.WNOHANG)
                        if res_pid == pid:
                            sys.stdout.write(f"[PTY] Process {s_id} exited\n")
                            sys.stdout.flush()
                            s["exit_code"] = os.WEXITSTATUS(status) if os.WIFEXITED(status) else (128 + os.WTERMSIG(status))
                            s["is_alive"] = False
                            s["exit_time"] = time.time()
                            s["pid"] = None
                            try:
                                if s.get("master_fd"):
                                    os.close(s["master_fd"])
                            except Exception:
                                pass
                            s["master_fd"] = None
                            _save_status()
                    except ChildProcessError:
                        pass

            # Build select list
            rlist = [server_sock]
            for c_fd, c in list(clients.items()):
                rlist.append(c["sock"])
            for s_id, s in list(sessions.items()):
                if s.get("master_fd"):
                    rlist.append(s["master_fd"])

            try:
                readable, _, _ = select.select(rlist, [], rlist, 0.05)
            except (select.error, OSError):
                continue

            for r in readable:
                if r is server_sock:
                    # Accept new client
                    try:
                        client_sock, addr = server_sock.accept()
                        client_sock.setblocking(False)
                        clients[client_sock.fileno()] = {
                            "sock": client_sock,
                            "session_id": None,
                            "buffer": b""
                        }
                    except Exception:
                        pass

                elif isinstance(r, socket.socket):
                    # Client socket data (keystrokes or handshake)
                    fd = r.fileno()
                    c_data = clients.get(fd)
                    if not c_data:
                        continue
                    try:
                        data = r.recv(4096)
                        if not data:
                            # Client disconnected cleanly
                            if c_data.get("session_id") in sessions:
                                sessions[c_data["session_id"]]["socket"] = None
                            clients.pop(fd, None)
                            try:
                                r.close()
                            except Exception:
                                pass
                            continue

                        c_data["buffer"] += data

                        # Check for handshake if session_id not set
                        if not c_data["session_id"]:
                            if b"\n" in c_data["buffer"]:
                                line, remainder = c_data["buffer"].split(b"\n", 1)
                                c_data["buffer"] = remainder
                                header = line.decode("utf-8", "replace").strip()
                                parts = header.split(":")
                                if len(parts) >= 3 and parts[0] in ("CONNECT", "START"):
                                    s_id = parts[1]
                                    a_type = parts[2]
                                    rows = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else 24
                                    cols = int(parts[4]) if len(parts) > 4 and parts[4].isdigit() else 80
                                    
                                    s = spawn_session(s_id, a_type, rows, cols)
                                    c_data["session_id"] = s_id
                                    s["socket"] = r
                                    focused_session = s_id
                                    _save_status()
                                    sys.stdout.write(f"[PTY] Client attached to {s_id}\n")
                                    sys.stdout.flush()

                                    # Send cached history so newly connected emulator paints existing screen state
                                    if s.get("history"):
                                        try:
                                            r.sendall(s["history"])
                                        except Exception:
                                            pass

                                    # If there is remaining data, write to PTY
                                    if c_data["buffer"]:
                                        s["last_input_time"] = time.time()
                                        s["attention_since"] = 0.0
                                        os.write(s["master_fd"], c_data["buffer"])
                                        c_data["buffer"] = b""
                        else:
                            # Connected session: check for control messages or raw keystrokes
                            s_id = c_data["session_id"]
                            s = sessions.get(s_id)
                            if s and s.get("master_fd"):
                                if data.startswith(b"RESIZE:"):
                                    parts = data.decode("utf-8", "replace").strip().split(":")
                                    if len(parts) == 3:
                                        r_cnt = int(parts[1])
                                        c_cnt = int(parts[2])
                                        set_winsize(s["master_fd"], r_cnt, c_cnt)
                                        focused_session = s_id
                                        _save_status()
                                else:
                                    s["last_input_time"] = time.time()
                                    s["attention_since"] = 0.0
                                    os.write(s["master_fd"], data)
                    except (ConnectionResetError, BrokenPipeError):
                        if c_data.get("session_id") in sessions:
                            sessions[c_data["session_id"]]["socket"] = None
                        clients.pop(fd, None)
                        try:
                            r.close()
                        except Exception:
                            pass
                    except Exception as e:
                        sys.stderr.write(f"[PTY] Socket error: {e}\n")

                else:
                    # One of the master_fds has output from a shell/agent process
                    master_fd = r
                    matching_session = None
                    for s_id, s in sessions.items():
                        if s.get("master_fd") == master_fd:
                            matching_session = s
                            break

                    if matching_session:
                        try:
                            out = os.read(master_fd, 8192)
                            if out:
                                matching_session["last_output_time"] = time.time()
                                matching_session["had_activity"] = True
                                # Keep circular history (last 64KB) for re-attachment
                                matching_session["history"] = (matching_session.get("history", b"") + out)[-65536:]

                                # Forward raw output to attached client socket
                                sock = matching_session.get("socket")
                                if sock:
                                    try:
                                        sock.sendall(out)
                                    except Exception:
                                        matching_session["socket"] = None
                            else:
                                _handle_session_eof(matching_session)
                        except OSError:
                            _handle_session_eof(matching_session)
        except Exception as e:
            sys.stderr.write(f"[PTY] Server error in main loop: {e}\n")
            time.sleep(0.1)

if __name__ == "__main__":
    main()