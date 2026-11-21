# ==============================================================================
# MobCode: Persistent Session State Manager
# Keeps sessions persistent across screen transitions even if daemon restarts
# ==============================================================================
extends Node

signal sessions_updated(sessions: Array)
signal active_session_changed(session_id: String)
signal daemon_connection_changed(online: bool)
signal live_pane_updated(terminal_text: String)
signal log_updated(log_text: String)
signal session_floating_changed(session_id: String, enabled: bool)
signal overlay_state_changed(active: bool, permission: bool, pending: bool)

const STATUS_PATH_SDCARD = "/sdcard/.mobcode_status.json"
const STATUS_PATH_STORAGE = "/storage/emulated/0/.mobcode_status.json"
const PANE_LOG_PATH = "/sdcard/.mobcode_pane.log"
const LOG_PATH = "/sdcard/.mobcode_log"
const PERSISTENT_STORE = "user://sessions.json"

const PANE_POLL_INTERVAL = 0.12 # 120ms for fast real-time terminal streaming
const STATUS_POLL_INTERVAL = 1.0 # 1000ms for status/session updates

var active_sessions: Array = []
var tracked_sessions: Dictionary = {} # Key: session_id -> {id, type, status, pid}
var current_selected_session: String = ""
var is_daemon_online: bool = false
var pane_timer: Timer
var status_timer: Timer
var _last_pane_text: String = ""
var pending_float_session_ids: Array[String] = []
var pending_float_all: bool = false
var _overlay_permission_available: bool = false

func _ready() -> void:
	DebugLogger.log_msg("SessionManager", "Initializing SessionManager autoload...")
	_load_persistent_sessions()

	# Fast timer for terminal pane streaming (120ms)
	pane_timer = Timer.new()
	pane_timer.wait_time = PANE_POLL_INTERVAL
	pane_timer.autostart = true
	pane_timer.timeout.connect(_refresh_live_pane)
	add_child(pane_timer)

	# Normal timer for status updates & daemon watchdog (1000ms)
	status_timer = Timer.new()
	status_timer.wait_time = STATUS_POLL_INTERVAL
	status_timer.autostart = true
	status_timer.timeout.connect(_on_status_timer_timeout)
	add_child(status_timer)

	_overlay_permission_available = _can_draw_overlay()
	_refresh_all()
	# Auto-start: write a trigger file the daemon setup can detect
	if not is_daemon_online:
		TermuxBridge.write_daemon_trigger()

var daemon_start_cooldown: float = 0.0

func _on_status_timer_timeout() -> void:
	_refresh_status()
	_refresh_logs()
	_poll_overlay_permission()
	if not is_daemon_online:
		daemon_start_cooldown += STATUS_POLL_INTERVAL
		if daemon_start_cooldown >= 10.0:
			daemon_start_cooldown = 0.0
			TermuxBridge.write_daemon_trigger()
	else:
		daemon_start_cooldown = 0.0

func _refresh_all() -> void:
	_refresh_status()
	_refresh_live_pane()
	_refresh_logs()

func _load_persistent_sessions() -> void:
	if FileAccess.file_exists(PERSISTENT_STORE):
		var file = FileAccess.open(PERSISTENT_STORE, FileAccess.READ)
		if file:
			var json_text = file.get_as_text()
			file.close()
			var json = JSON.new()
			if json.parse(json_text) == OK and typeof(json.data) == TYPE_DICTIONARY:
				tracked_sessions = json.data
				_rebuild_active_sessions_array()
				DebugLogger.log_msg("SessionManager", "Loaded persistent sessions: " + str(tracked_sessions.keys()))

func _save_persistent_sessions() -> void:
	var file = FileAccess.open(PERSISTENT_STORE, FileAccess.WRITE)
	if file:
		file.store_string(JSON.stringify(tracked_sessions))
		file.close()

func _rebuild_active_sessions_array() -> void:
	var arr = []
	for s_id in tracked_sessions:
		arr.append(tracked_sessions[s_id])
	active_sessions = arr
	sessions_updated.emit(active_sessions)
	_sync_floating_to_native()

func toggle_session_floating(session_id: String) -> bool:
	if not tracked_sessions.has(session_id):
		return false
	var cur := bool(tracked_sessions[session_id].get("floating", false))
	if not cur and not _can_draw_overlay():
		if not pending_float_session_ids.has(session_id):
			pending_float_session_ids.append(session_id)
		pending_float_all = false
		_request_overlay_permission()
		_emit_overlay_state()
		DebugLogger.log_msg("SessionManager", "Floating overlay permission requested for " + session_id)
		return false
	var new_val := not cur
	tracked_sessions[session_id]["floating"] = new_val
	pending_float_session_ids.erase(session_id)
	_save_persistent_sessions()
	_rebuild_active_sessions_array()
	session_floating_changed.emit(session_id, new_val)
	_emit_overlay_state()
	DebugLogger.log_msg("SessionManager", "Toggled floating status for " + session_id + " to " + str(new_val))
	return new_val

func set_all_floating(enabled: bool) -> bool:
	if enabled and not _can_draw_overlay():
		pending_float_all = true
		pending_float_session_ids.clear()
		_request_overlay_permission()
		_emit_overlay_state()
		DebugLogger.log_msg("SessionManager", "Floating overlay permission requested for all sessions")
		return false
	pending_float_all = false
	pending_float_session_ids.clear()
	var changed := false
	for key in tracked_sessions.keys():
		var s_id := str(key)
		var data: Dictionary = tracked_sessions[s_id]
		if bool(data.get("floating", false)) != enabled:
			data["floating"] = enabled
			tracked_sessions[s_id] = data
			changed = true
			session_floating_changed.emit(s_id, enabled)
	if changed:
		_save_persistent_sessions()
		_rebuild_active_sessions_array()
	_emit_overlay_state()
	return enabled

func has_pinned_sessions() -> bool:
	for s in active_sessions:
		if bool(s.get("floating", false)):
			return true
	return false

func pinned_session_count() -> int:
	var count := 0
	for s in active_sessions:
		if bool(s.get("floating", false)):
			count += 1
	return count

func overlay_state() -> Dictionary:
	return {
		"active": has_pinned_sessions(),
		"permission": _can_draw_overlay(),
		"pending": _has_pending_floating()
	}

func _has_pending_floating() -> bool:
	return pending_float_all or not pending_float_session_ids.is_empty()

func _can_draw_overlay() -> bool:
	if not Engine.has_singleton("MobCodeTerminal"):
		return false
	var plugin = Engine.get_singleton("MobCodeTerminal")
	if plugin != null and plugin.has_method("canDrawOverlays"):
		return bool(plugin.canDrawOverlays())
	return false

func _request_overlay_permission() -> void:
	if not Engine.has_singleton("MobCodeTerminal"):
		return
	var plugin = Engine.get_singleton("MobCodeTerminal")
	if plugin.has_method("checkAndRequestOverlayPermission"):
		plugin.checkAndRequestOverlayPermission()
	elif plugin.has_method("requestOverlayPermission"):
		plugin.requestOverlayPermission()

func _emit_overlay_state() -> void:
	overlay_state_changed.emit(has_pinned_sessions(), _can_draw_overlay(), _has_pending_floating())

func _poll_overlay_permission() -> void:
	var available := _can_draw_overlay()
	var changed := available != _overlay_permission_available
	_overlay_permission_available = available
	if available and _has_pending_floating():
		_complete_pending_floating()
	if changed:
		DebugLogger.log_msg("SessionManager", "Overlay permission changed to " + str(available))
		_emit_overlay_state()
		if not available:
			_sync_floating_to_native()

func _complete_pending_floating() -> void:
	var ids: Array[String] = []
	if pending_float_all:
		for key in tracked_sessions.keys():
			ids.append(str(key))
	else:
		ids.assign(pending_float_session_ids)
	pending_float_all = false
	pending_float_session_ids.clear()
	var changed := false
	for s_id in ids:
		if not tracked_sessions.has(s_id):
			continue
		var data: Dictionary = tracked_sessions[s_id]
		if not bool(data.get("floating", false)):
			data["floating"] = true
			tracked_sessions[s_id] = data
			changed = true
			session_floating_changed.emit(s_id, true)
	if changed:
		_save_persistent_sessions()
		_rebuild_active_sessions_array()
	_emit_overlay_state()
	if changed:
		DebugLogger.log_msg("SessionManager", "Completed pending floating overlay pins")

func is_session_floating(session_id: String) -> bool:
	if tracked_sessions.has(session_id):
		return tracked_sessions[session_id].get("floating", false)
	return false

func sync_floating_to_native() -> void:
	_sync_floating_to_native()

func _sync_floating_to_native() -> void:
	if not Engine.has_singleton("MobCodeTerminal"):
		return
	var plugin = Engine.get_singleton("MobCodeTerminal")
	if plugin.has_method("updateFloatingSessions"):
		var list = []
		for s in active_sessions:
			var s_id := str(s.get("id", ""))
			if s_id == "":
				continue
			list.append({
				"id": s_id,
				"type": s.get("type", s.get("agent", "shell")),
				"status": s.get("status", "idle"),
				"pinned": bool(s.get("floating", false))
			})
		plugin.updateFloatingSessions(JSON.stringify(list))

func _refresh_status() -> void:
	var file_path = ""
	if FileAccess.file_exists(STATUS_PATH_SDCARD):
		file_path = STATUS_PATH_SDCARD
	elif FileAccess.file_exists(STATUS_PATH_STORAGE):
		file_path = STATUS_PATH_STORAGE

	if file_path == "":
		_set_daemon_online(false)
		return

	var file = FileAccess.open(file_path, FileAccess.READ)
	if not file:
		_set_daemon_online(false)
		return

	var content = file.get_as_text()
	file.close()

	if content.is_empty():
		return

	var json = JSON.new()
	var error = json.parse(content)
	if error != OK:
		return

	var data = json.data
	if typeof(data) == TYPE_DICTIONARY:
		var ts = data.get("timestamp", 0)
		var current_unix = int(Time.get_unix_time_from_system())
		
		# Check freshness of timestamp
		if abs(current_unix - ts) <= 30 and data.get("daemon_online", false):
			_set_daemon_online(true)
		else:
			_set_daemon_online(false)

		if data.has("sessions"):
			var daemon_sessions = data["sessions"]
			var changed = false
			var live_ids: Dictionary = {}

			# Update tracked sessions with live daemon status
			for ds in daemon_sessions:
				var s_id = ds.get("id", "")
				if s_id != "":
					live_ids[s_id] = true
					if tracked_sessions.has(s_id):
						if tracked_sessions[s_id].get("status") != ds.get("status"):
							tracked_sessions[s_id]["status"] = ds.get("status")
							changed = true
						var was_exited := bool(tracked_sessions[s_id].get("exited", false))
						if bool(ds.get("exited", false)):
							tracked_sessions[s_id]["exited"] = true
						elif was_exited:
							tracked_sessions[s_id].erase("exited")
						if bool(ds.get("exited", false)) != was_exited:
							changed = true
					elif not bool(ds.get("exited", false)):
						tracked_sessions[s_id] = ds
						changed = true

			# Prune entries that no longer have a live PTY or tmux process.
			# Only do this when the daemon reports a merged view, otherwise
			# PTY-only sessions would be removed incorrectly.
			if data.get("merged", false) and is_daemon_online:
				var stale_ids: Array = []
				for s_id in tracked_sessions.keys():
					if live_ids.has(s_id):
						continue
					var status = str(tracked_sessions[s_id].get("status", ""))
					var created_at = int(tracked_sessions[s_id].get("created_at", 0))
					if status == "pending" and created_at > 0 and current_unix - created_at < 30:
						continue
					stale_ids.append(s_id)
				for s_id in stale_ids:
					tracked_sessions.erase(s_id)
					changed = true
					if current_selected_session == s_id:
						current_selected_session = ""

			if changed:
				_save_persistent_sessions()
				_rebuild_active_sessions_array()

func _refresh_live_pane() -> void:
	if FileAccess.file_exists(PANE_LOG_PATH):
		var file = FileAccess.open(PANE_LOG_PATH, FileAccess.READ)
		if file:
			var text = file.get_as_text()
			file.close()
			if text != _last_pane_text:
				_last_pane_text = text
				live_pane_updated.emit(text)

func _refresh_logs() -> void:
	if FileAccess.file_exists(LOG_PATH):
		var file = FileAccess.open(LOG_PATH, FileAccess.READ)
		if file:
			var text = file.get_as_text()
			file.close()
			log_updated.emit(text)

func _set_daemon_online(online: bool) -> void:
	if is_daemon_online != online:
		is_daemon_online = online
		daemon_connection_changed.emit(online)
		DebugLogger.log_msg("SessionManager", "Daemon connection state changed: " + str(online))

## Creates a new session with custom name and persists it
func create_custom_session(session_id: String, agent_type: String) -> void:
	DebugLogger.log_msg("SessionManager", "Creating session: " + session_id + " (" + agent_type + ")")
	tracked_sessions[session_id] = {
		"id": session_id,
		"type": agent_type,
		"status": "pending",
		"pid": 0,
		"created_at": int(Time.get_unix_time_from_system())
	}
	_save_persistent_sessions()
	_rebuild_active_sessions_array()

	# The native terminal spawns the PTY session when openTerminal() is called.
	# Starting tmux here as well would create an unwanted duplicate session.
	if not Engine.has_singleton("MobCodeTerminal"):
		TermuxBridge.start_session(session_id, agent_type)
	select_session(session_id, false)

func select_session(session_id: String, send_focus: bool = true) -> void:
	current_selected_session = session_id
	if send_focus:
		TermuxBridge.focus_session(session_id)
	active_session_changed.emit(session_id)
	DebugLogger.log_msg("SessionManager", "Selected session: " + session_id)

func stop_session(session_id: String) -> void:
	DebugLogger.log_msg("SessionManager", "Stopping session: " + session_id)
	pending_float_session_ids.erase(session_id)
	tracked_sessions.erase(session_id)
	_save_persistent_sessions()
	_rebuild_active_sessions_array()

	if Engine.has_singleton("MobCodeTerminal"):
		var plugin = Engine.get_singleton("MobCodeTerminal")
		if plugin.has_method("closeSession"):
			plugin.closeSession(session_id)
	TermuxBridge.stop_session(session_id)
	if current_selected_session == session_id:
		current_selected_session = ""
		if tracked_sessions.size() > 0:
			select_session(tracked_sessions.keys()[0])

func pause_session(session_id: String) -> void:
	DebugLogger.log_msg("SessionManager", "Pausing session: " + session_id)
	if tracked_sessions.has(session_id):
		tracked_sessions[session_id]["status"] = "paused"
		_rebuild_active_sessions_array()
	TermuxBridge.pause_session(session_id)

func resume_session(session_id: String) -> void:
	DebugLogger.log_msg("SessionManager", "Resuming session: " + session_id)
	if tracked_sessions.has(session_id):
		tracked_sessions[session_id]["status"] = "running"
		_rebuild_active_sessions_array()
	TermuxBridge.resume_session(session_id)

func toggle_pause(session_id: String) -> void:
	if tracked_sessions.has(session_id):
		if tracked_sessions[session_id].get("status") == "paused":
			resume_session(session_id)
		else:
			pause_session(session_id)

func send_key(session_id: String, key_name: String) -> void:
	TermuxBridge.send_key(session_id, key_name)
	get_tree().create_timer(0.08).timeout.connect(_refresh_live_pane)

func stop_all_sessions() -> void:
	DebugLogger.log_msg("SessionManager", "Stopping all sessions")
	pending_float_session_ids.clear()
	pending_float_all = false
	tracked_sessions.clear()
	_save_persistent_sessions()
	_rebuild_active_sessions_array()

	if Engine.has_singleton("MobCodeTerminal"):
		var plugin = Engine.get_singleton("MobCodeTerminal")
		if plugin.has_method("closeTerminal"):
			plugin.closeTerminal()
	TermuxBridge.stop_all()
	current_selected_session = ""

func send_prompt_to_session(prompt_text: String, session_id: String = "") -> void:
	var target = session_id if session_id != "" else current_selected_session
	if target != "":
		DebugLogger.log_msg("SessionManager", "Sending prompt to " + target + ": " + prompt_text)
		TermuxBridge.send_prompt(target, prompt_text)
		get_tree().create_timer(0.08).timeout.connect(_refresh_live_pane)
