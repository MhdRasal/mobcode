# ==============================================================================
# MobCode: In-App Debug Log Viewer
# ==============================================================================
class_name LogViewerDialog
extends AcceptDialog

@onready var log_text: RichTextLabel = $VBox/LogText
@onready var copy_btn: Button = $VBox/HBox/CopyButton
@onready var refresh_btn: Button = $VBox/HBox/RefreshButton
@onready var clear_btn: Button = $VBox/HBox/ClearButton

func _ready() -> void:
    theme = MobCodeTheme.create()
    title = "Debug logs"
    copy_btn.pressed.connect(_on_copy_pressed)
    refresh_btn.pressed.connect(refresh_log)
    clear_btn.pressed.connect(_on_clear_pressed)

func show_logs() -> void:
    refresh_log()
    popup_centered(Vector2i(420, 560))

func refresh_log() -> void:
    if FileAccess.file_exists(DebugLogger.DEBUG_LOG_PATH):
        var file = FileAccess.open(DebugLogger.DEBUG_LOG_PATH, FileAccess.READ)
        if file:
            log_text.text = file.get_as_text()
            file.close()
    else:
        log_text.text = "No debug log file found at " + DebugLogger.DEBUG_LOG_PATH

func _on_copy_pressed() -> void:
    DisplayServer.clipboard_set(log_text.text)
    copy_btn.text = "Copied"
    var tween = create_tween()
    tween.tween_interval(1.5)
    tween.tween_callback(func(): copy_btn.text = "Copy log")

func _on_clear_pressed() -> void:
    DebugLogger.clear_log()
    refresh_log()
