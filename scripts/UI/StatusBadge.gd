# ==============================================================================
# MobCode: Floating Draggable Status Overlay Badge
# ==============================================================================
class_name StatusBadge
extends PanelContainer

signal clicked

@onready var status_dot: ColorRect = $HBox/StatusDot
@onready var count_label: Label = $HBox/CountLabel

var is_dragging: bool = false
var drag_start_position: Vector2
var touch_start_position: Vector2

const COLOR_RUNNING = Color("#2ecc71")
const COLOR_WAITING = Color("#f1c40f")
const COLOR_IDLE = Color("#95a5a6")
const COLOR_OFF = Color("#7f8c8d")

func _ready() -> void:
    theme = MobCodeTheme.create()
    add_theme_stylebox_override("panel", MobCodeTheme.panel_style(MobCodeTheme.SURFACE_2, MobCodeTheme.BORDER, 99, 8))
    SessionManager.sessions_updated.connect(_on_sessions_updated)
    _on_sessions_updated(SessionManager.active_sessions)
    _start_pulse_animation()

func _gui_input(event: InputEvent) -> void:
    if event is InputEventScreenTouch or event is InputEventMouseButton:
        if event.pressed:
            is_dragging = false
            touch_start_position = event.position
            drag_start_position = global_position
        else:
            # Check if it was a click or a drag release
            if not is_dragging:
                clicked.emit()
            else:
                _snap_to_screen_edge()
            is_dragging = false

    elif event is InputEventScreenDrag or (event is InputEventMouseMotion and is_dragging):
        if not is_dragging and (event.position - touch_start_position).length() > 10:
            is_dragging = true
        if is_dragging:
            global_position += event.relative

func _snap_to_screen_edge() -> void:
    var viewport_rect = get_viewport_rect()
    var target_x = 20.0
    if global_position.x > viewport_rect.size.x / 2.0:
        target_x = viewport_rect.size.x - size.x - 20.0
    
    var target_y = clamp(global_position.y, 40.0, viewport_rect.size.y - size.y - 40.0)
    var tween = create_tween().set_trans(Tween.TRANS_SPRING).set_ease(Tween.EASE_OUT)
    tween.tween_property(self, "global_position", Vector2(target_x, target_y), 0.3)

func _on_sessions_updated(sessions: Array) -> void:
    var total = sessions.size()
    count_label.text = str(total)

    if total == 0:
        status_dot.color = COLOR_OFF
        visible = false
        return

    visible = true
    var has_running = false
    var has_waiting = false

    for s in sessions:
        var status = s.get("status", "idle")
        if status == "running":
            has_running = true
        elif status == "waiting":
            has_waiting = true

    if has_running:
        status_dot.color = COLOR_RUNNING
    elif has_waiting:
        status_dot.color = COLOR_WAITING
    else:
        status_dot.color = COLOR_IDLE

func _start_pulse_animation() -> void:
    var tween = create_tween().set_loops()
    tween.tween_property(status_dot, "modulate:a", 0.3, 0.8)
    tween.tween_property(status_dot, "modulate:a", 1.0, 0.8)
