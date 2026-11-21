# ==============================================================================
# MobCode: Slide-out Sidebar Drawer
# ==============================================================================
class_name Sidebar
extends PanelContainer

signal closed
signal session_selected(session_id: String)

@onready var session_list_container: VBoxContainer = $VBox/Scroll/SessionList
@onready var top_safe_area: Control = $VBox/TopSafeArea
@onready var count_badge: Label = $VBox/Header/CountBadge
@onready var close_btn: Button = $VBox/Header/CloseButton
@onready var stop_all_btn: Button = $VBox/Footer/StopAllButton
@onready var floating_status_btn: Button = $VBox/Footer/FloatingStatusButton

var session_card_scene = preload("res://scenes/components/SessionCard.tscn")
var is_open: bool = false
var slide_tween: Tween

func _ready() -> void:
	add_theme_stylebox_override("panel", MobCodeTheme.panel_style(
		MobCodeTheme.SURFACE, MobCodeTheme.BORDER, 18, 12
	))

	close_btn.pressed.connect(hide_sidebar)
	stop_all_btn.pressed.connect(_on_stop_all_pressed)
	floating_status_btn.pressed.connect(_on_floating_status_pressed)
	SessionManager.sessions_updated.connect(_on_sessions_updated)
	SessionManager.session_floating_changed.connect(_on_floating_changed)
	SessionManager.overlay_state_changed.connect(_on_overlay_state_changed)
	_on_sessions_updated(SessionManager.active_sessions)
	_update_floating_button()
	
	call_deferred("_place_offscreen")

	_apply_safe_area()
	_update_sidebar_width()
	get_viewport().size_changed.connect(_apply_safe_area)
	get_viewport().size_changed.connect(_update_sidebar_width)

func _apply_safe_area() -> void:
	var insets := SafeArea.get_insets(get_viewport().get_visible_rect().size)
	var final_top := maxf(16.0, float(insets.get("top", 0.0)) + 12.0)
	if top_safe_area:
		top_safe_area.custom_minimum_size.y = final_top

func _update_sidebar_width() -> void:
	if not is_inside_tree():
		return
	var viewport_width := get_viewport().get_visible_rect().size.x
	var target := clampf(viewport_width - 24.0, 260.0, 300.0)
	custom_minimum_size.x = target
	offset_right = target
	session_list_container.custom_minimum_size.x = 0.0
	if not is_open:
		position.x = -size.x

func _place_offscreen() -> void:
	position.x = -size.x

func show_sidebar() -> void:
	is_open = true
	if slide_tween and slide_tween.is_valid():
		slide_tween.kill()
	slide_tween = create_tween().set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	slide_tween.tween_property(self, "position:x", 0.0, 0.25)

func hide_sidebar() -> void:
	is_open = false
	if slide_tween and slide_tween.is_valid():
		slide_tween.kill()
	slide_tween = create_tween().set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_IN)
	slide_tween.tween_property(self, "position:x", -size.x, 0.25)
	closed.emit()

func toggle() -> void:
	if is_open:
		hide_sidebar()
	else:
		show_sidebar()

func _on_sessions_updated(sessions: Array) -> void:
	count_badge.text = str(sessions.size())
	_update_floating_button()

	# Clear old cards
	for child in session_list_container.get_children():
		child.queue_free()

	if sessions.size() == 0:
		var empty_lbl := Label.new()
		empty_lbl.text = "No active sessions.\nUse the dashboard to start one."
		empty_lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		empty_lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		empty_lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		empty_lbl.add_theme_color_override("font_color", MobCodeTheme.MUTED_DIM)
		session_list_container.add_child(empty_lbl)
		return

	# Instantiate cards
	for s_data in sessions:
		var card = session_card_scene.instantiate()
		session_list_container.add_child(card)
		card.setup(s_data)
		card.session_selected.connect(func(id):
			hide_sidebar()
			session_selected.emit(id)
		)

func _on_floating_changed(_session_id: String, _enabled: bool) -> void:
	_update_floating_button()

func _on_overlay_state_changed(_active: bool, _permission: bool, _pending: bool) -> void:
	_update_floating_button()

func _update_floating_button() -> void:
	var state: Dictionary = SessionManager.overlay_state()
	var pinned := SessionManager.pinned_session_count()
	if bool(state.get("pending", false)):
		floating_status_btn.text = "Allow floating overlay"
	elif pinned > 0 and not bool(state.get("permission", false)):
		floating_status_btn.text = "Floating overlay · permission off"
	elif pinned > 0:
		floating_status_btn.text = "Floating overlay · %d" % pinned
	else:
		floating_status_btn.text = "Floating overlay"

func _on_floating_status_pressed() -> void:
	SessionManager.set_all_floating(not SessionManager.has_pinned_sessions())
	_update_floating_button()

func _on_stop_all_pressed() -> void:
	SessionManager.stop_all_sessions()
	hide_sidebar()
