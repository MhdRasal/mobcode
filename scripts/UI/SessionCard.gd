# ==============================================================================
# MobCode: Session Card UI Widget
# ==============================================================================
class_name SessionCard
extends PanelContainer

signal session_selected(session_id: String)
signal session_stopped(session_id: String)

@onready var icon_panel: PanelContainer = $HBox/IconPanel
@onready var icon_rect: TextureRect = $HBox/IconPanel/Icon
@onready var name_label: Label = $HBox/VBox/NameLabel
@onready var type_label: Label = $HBox/VBox/TypeLabel
@onready var status_panel: PanelContainer = $HBox/StatusPill
@onready var status_dot: ColorRect = $HBox/StatusPill/HBox/StatusDot
@onready var status_label: Label = $HBox/StatusPill/HBox/StatusLabel
@onready var float_button: Button = $HBox/FloatButton
@onready var close_button: Button = $HBox/CloseButton

var current_session_id: String = ""
var current_agent_type: String = ""

func setup(session_data: Dictionary) -> void:
	current_session_id = str(session_data.get("id", "unknown"))
	current_agent_type = str(session_data.get("type", "shell"))
	var status := str(session_data.get("status", "idle"))
	var accent := AgentRegistry.accent_for(current_agent_type)
	var status_color := AgentRegistry.status_color(status)

	name_label.text = current_session_id
	type_label.text = AgentRegistry.type_label(current_agent_type)
	type_label.add_theme_color_override("font_color", accent)
	icon_rect.texture = AgentRegistry.icon_for(current_agent_type)
	icon_panel.add_theme_stylebox_override("panel", MobCodeTheme.panel_style(
		Color(accent.r, accent.g, accent.b, 0.12),
		Color(accent.r, accent.g, accent.b, 0.38),
		12,
		8
	))
	var card_style: StyleBoxFlat = MobCodeTheme.card_style(accent)
	card_style.content_margin_left = 10
	card_style.content_margin_right = 8
	card_style.content_margin_top = 8
	card_style.content_margin_bottom = 8
	add_theme_stylebox_override("panel", card_style)

	status_label.text = AgentRegistry.status_short_label(status)
	status_label.add_theme_color_override("font_color", status_color)
	status_dot.color = status_color
	status_panel.add_theme_stylebox_override("panel", MobCodeTheme.pill_style(status_color))

	if not float_button.pressed.is_connected(_on_float_pressed):
		float_button.pressed.connect(_on_float_pressed)
	if not close_button.pressed.is_connected(_on_close_pressed):
		close_button.pressed.connect(_on_close_pressed)

	var is_floating: bool = bool(session_data.get("floating", false))
	_update_float_button_ui(is_floating)

func _update_float_button_ui(floating: bool) -> void:
	if float_button:
		if floating:
			float_button.modulate = Color("#A78BFA")
			float_button.tooltip_text = "Floating status on (tap to unpin)"
		else:
			float_button.modulate = Color("#71717A")
			float_button.tooltip_text = "Pin floating status overlay"

func _on_float_pressed() -> void:
	var new_state: bool = SessionManager.toggle_session_floating(current_session_id)
	_update_float_button_ui(new_state)
	if not new_state and SessionManager.overlay_state().get("pending", false):
		float_button.tooltip_text = "Allow overlay permission, then tap again"

func _gui_input(event: InputEvent) -> void:
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT and event.pressed:
		_on_open_pressed()

func _on_open_pressed() -> void:
	session_selected.emit(current_session_id)

func _on_close_pressed() -> void:
	session_stopped.emit(current_session_id)
	SessionManager.stop_session(current_session_id)
