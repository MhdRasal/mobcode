# ==============================================================================
# MobCode: Termux IPC Bridge (File-Based IPC Only — No JNI Required)
# ==============================================================================
class_name TermuxBridge
extends Node

const CMD_QUEUE_PATH  = "/sdcard/.mobcode_cmd"
const CMD_QUEUE_DIR   = "/sdcard/.mobcode_cmd_queue"
const DAEMON_TRIGGER  = "/sdcard/.mobcode_start_daemon"
const STATUS_PATH     = "/sdcard/.mobcode_status.json"
const DAEMON_SRC      = "/sdcard/godot/mobcode/backend/mobcode_daemon.sh"
const LAUNCHER_SRC    = "/sdcard/godot/mobcode/backend/mobcode_launcher.sh"
const SETUP_CMD       = "bash /sdcard/godot/mobcode/backend/setup_termux.sh"

static var _cmd_counter: int = 0

## Dispatches a command by writing to the shared spool queue (no race conditions)
static func dispatch_command(action: String, session_id: String = "", agent_type: String = "", payload: String = "") -> void:
	var cmd_payload = "%s:%s:%s:%s" % [action, session_id, agent_type, payload]
	_cmd_counter += 1
	var timestamp = int(Time.get_unix_time_from_system() * 1000)
	var queue_file = "%s/%d_%04d.cmd" % [CMD_QUEUE_DIR, timestamp, _cmd_counter % 10000]

	if not DirAccess.dir_exists_absolute(CMD_QUEUE_DIR):
		DirAccess.make_dir_recursive_absolute(CMD_QUEUE_DIR)

	var file = FileAccess.open(queue_file, FileAccess.WRITE)
	if file:
		file.store_string(cmd_payload)
		file.close()
		DebugLogger.log_msg("TermuxBridge", "CMD [queue] >> " + cmd_payload)
	else:
		# Fallback to direct file
		var fallback = FileAccess.open(CMD_QUEUE_PATH, FileAccess.WRITE)
		if fallback:
			fallback.store_string(cmd_payload)
			fallback.close()
			DebugLogger.log_msg("TermuxBridge", "CMD [fallback] >> " + cmd_payload)
		else:
			DebugLogger.log_msg("TermuxBridge", "ERROR: Cannot write to command queue")

## Writes a trigger file that tells the daemon watcher to start.
## The setup_termux.sh script installs a .bashrc hook that checks for this file.
static func write_daemon_trigger() -> void:
	var f = FileAccess.open(DAEMON_TRIGGER, FileAccess.WRITE)
	if f:
		f.store_string(str(int(Time.get_unix_time_from_system())))
		f.close()
		DebugLogger.log_msg("TermuxBridge", "Daemon trigger written to " + DAEMON_TRIGGER)
	else:
		DebugLogger.log_msg("TermuxBridge", "ERROR: Cannot write daemon trigger")

## Opens Termux app or starts the engine in background via native plugin
static func launch_termux_to_start_engine() -> void:
	write_daemon_trigger()
	if Engine.has_singleton("MobCodeTerminal"):
		var plugin = Engine.get_singleton("MobCodeTerminal")
		if plugin.has_method("startEngine"):
			plugin.startEngine()
			DebugLogger.log_msg("TermuxBridge", "Started engine via MobCodeTerminal plugin")
	if OS.get_name() == "Android":
		# Try multiple URI schemes to open Termux
		OS.shell_open("com.termux://")
		DebugLogger.log_msg("TermuxBridge", "Launched Termux app via shell_open")
	else:
		DebugLogger.log_msg("TermuxBridge", "Mock: Would launch Termux")

static func start_session(session_id: String, agent_type: String) -> void:
	dispatch_command("start", session_id, agent_type)

static func stop_session(session_id: String) -> void:
	dispatch_command("stop", session_id)

static func pause_session(session_id: String) -> void:
	dispatch_command("pause", session_id)

static func resume_session(session_id: String) -> void:
	dispatch_command("resume", session_id)

static func send_key(session_id: String, key_name: String) -> void:
	dispatch_command("send_key", session_id, "", key_name)

static func focus_session(session_id: String) -> void:
	dispatch_command("focus", session_id)

static func stop_all() -> void:
	dispatch_command("stop_all")

static func send_prompt(session_id: String, prompt_text: String) -> void:
	dispatch_command("send_prompt", session_id, "", prompt_text)

static func open_in_termux(session_id: String = "") -> void:
	if session_id != "":
		dispatch_command("attach", session_id)
	launch_termux_to_start_engine()

static func launch_termux_app() -> void:
	launch_termux_to_start_engine()

static func copy_setup_command() -> void:
	DisplayServer.clipboard_set(SETUP_CMD)
	DebugLogger.log_msg("TermuxBridge", "Copied setup command to clipboard: " + SETUP_CMD)

## Check if daemon appears online based on status file freshness
static func is_daemon_running() -> bool:
	if not FileAccess.file_exists(STATUS_PATH):
		return false
	var f = FileAccess.open(STATUS_PATH, FileAccess.READ)
	if not f:
		return false
	var content = f.get_as_text()
	f.close()
	var json = JSON.new()
	if json.parse(content) != OK:
		return false
	var data = json.data
	if typeof(data) != TYPE_DICTIONARY:
		return false
	var ts = data.get("timestamp", 0)
	var now = int(Time.get_unix_time_from_system())
	return abs(now - ts) <= 30 and data.get("daemon_online", false)
