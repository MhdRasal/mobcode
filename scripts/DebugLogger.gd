# ==============================================================================
# MobCode: Persistent Debug Logger
# Logs timestamped entries to /sdcard/mobcode_debug.log and Godot console
# ==============================================================================
class_name DebugLogger
extends RefCounted

const DEBUG_LOG_PATH = "/sdcard/mobcode_debug.log"

static func log_msg(tag: String, message: String) -> void:
    var time_str = Time.get_datetime_string_from_system()
    var line = "[%s] [%s] %s\n" % [time_str, tag, message]
    print(line.strip_edges())

    var file: FileAccess
    if FileAccess.file_exists(DEBUG_LOG_PATH):
        file = FileAccess.open(DEBUG_LOG_PATH, FileAccess.READ_WRITE)
        if file:
            file.seek_end()
    else:
        file = FileAccess.open(DEBUG_LOG_PATH, FileAccess.WRITE)

    if file:
        file.store_string(line)
        file.close()

static func clear_log() -> void:
    var file = FileAccess.open(DEBUG_LOG_PATH, FileAccess.WRITE)
    if file:
        file.store_string("[MobCode Debug Log Initialized]\n")
        file.close()
