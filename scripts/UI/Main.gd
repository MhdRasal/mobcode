# ==============================================================================
# MobCode: Main Application Controller
# ==============================================================================
class_name Main
extends Control

@onready var sidebar: Sidebar = $Sidebar
@onready var menu_btn: Button = $VBox/TopBar/MenuButton
@onready var brand_icon: TextureRect = $VBox/TopBar/BrandIcon
@onready var logs_btn: Button = $VBox/TopBar/LogsButton
@onready var stop_all_btn: Button = $VBox/TopBar/StopAllButton
@onready var conn_status_lbl: Label = $VBox/TopBar/ConnectionPill/HBox/ConnectionStatusLabel
@onready var conn_dot: ColorRect = $VBox/TopBar/ConnectionPill/HBox/ConnectionDot
@onready var connection_pill: PanelContainer = $VBox/TopBar/ConnectionPill

# Setup / Diagnostic Banner
@onready var setup_banner: PanelContainer = $VBox/SetupBanner
@onready var copy_setup_btn: Button = $VBox/SetupBanner/VBox/Actions/CopySetupButton
@onready var open_termux_setup_btn: Button = $VBox/SetupBanner/VBox/Actions/OpenTermuxButton

# Agent launcher cards
@onready var quick_bar: GridContainer = $VBox/Content/QuickBar

# Active Sessions Container in Dashboard
@onready var sessions_scroll: ScrollContainer = $VBox/Content/SessionsPanel/VBox/Scroll
@onready var sessions_container: VBoxContainer = $VBox/Content/SessionsPanel/VBox/Scroll/SessionsContainer
@onready var empty_state_lbl: Label = $VBox/Content/SessionsPanel/VBox/EmptyLabel
@onready var sessions_count_label: Label = $VBox/Content/SessionsPanel/VBox/HeaderRow/CountLabel

# Terminal View & Dialogs
@onready var terminal_view: TerminalView = $TerminalView
@onready var new_session_dialog: NewSessionDialog = $NewSessionDialog
@onready var log_viewer_dialog: LogViewerDialog = $LogViewerDialog

var session_card_scene = preload("res://scenes/components/SessionCard.tscn")
var terminal_plugin = null

func _ready() -> void:
    theme = MobCodeTheme.create()
    connection_pill.add_theme_stylebox_override("panel", MobCodeTheme.panel_style(MobCodeTheme.SURFACE_2, MobCodeTheme.BORDER, 99, 8))
    setup_banner.add_theme_stylebox_override("panel", MobCodeTheme.panel_style(Color("#1C1917"), Color("#78350F"), 14, 12))

    DebugLogger.log_msg("Main", "MobCode Main UI Ready")

    if Engine.has_singleton("MobCodeTerminal"):
        terminal_plugin = Engine.get_singleton("MobCodeTerminal")
        if terminal_plugin.has_signal("terminal_closed"):
            terminal_plugin.terminal_closed.connect(_show_dashboard)
        SessionManager.sync_floating_to_native()
        DebugLogger.log_msg("Main", "MobCodeTerminal native plugin connected")

    # Connect UI signals
    menu_btn.pressed.connect(sidebar.toggle)
    sidebar.session_selected.connect(open_terminal_view)
    stop_all_btn.pressed.connect(SessionManager.stop_all_sessions)
    logs_btn.pressed.connect(log_viewer_dialog.show_logs)

    # Setup banner actions
    copy_setup_btn.pressed.connect(_on_copy_setup_pressed)
    open_termux_setup_btn.pressed.connect(TermuxBridge.launch_termux_app)

    # Quick launch cards -> open custom naming dialog
    for card in quick_bar.get_children():
        if card is QuickActionCard:
            card.pressed.connect(_on_quick_action_pressed.bind(card.agent_type))

    # New Session Dialog confirmed
    new_session_dialog.session_created.connect(_on_session_created)

    # Terminal view back button
    terminal_view.back_pressed.connect(_show_dashboard)
    terminal_view.visible = false

    # Session Manager updates
    SessionManager.sessions_updated.connect(_on_sessions_updated)
    SessionManager.daemon_connection_changed.connect(_on_connection_changed)

    _on_connection_changed(SessionManager.is_daemon_online)
    _on_sessions_updated(SessionManager.active_sessions)

    _apply_safe_area_insets()
    get_viewport().size_changed.connect(_apply_safe_area_insets)
    call_deferred("_apply_safe_area_insets")

func _apply_safe_area_insets() -> void:
    SafeArea.apply_to($VBox, 16.0, 12.0)
    if brand_icon:
        brand_icon.visible = $VBox.size.x >= 372.0

func _on_quick_action_pressed(agent_type: String) -> void:
    new_session_dialog.prompt_new_session(agent_type)

func _on_session_created(session_id: String, agent_type: String) -> void:
    SessionManager.create_custom_session(session_id, agent_type)
    open_terminal_view(session_id)

func open_terminal_view(session_id: String) -> void:
    if terminal_plugin:
        var agent_type = _get_agent_type(session_id)
        terminal_plugin.openTerminal(session_id, agent_type)
        return
    $VBox.visible = false
    terminal_view.visible = true
    terminal_view.open_session(session_id)

func _get_agent_type(session_id: String) -> String:
    for s in SessionManager.active_sessions:
        if s.get("id") == session_id:
            return s.get("agent", s.get("type", "shell"))
    if session_id.begins_with("agy"):
        return "agy"
    elif session_id.begins_with("opencode"):
        return "opencode"
    elif session_id.begins_with("claude"):
        return "claude"
    return "shell"

func _show_dashboard() -> void:
    terminal_view.visible = false
    $VBox.visible = true
    _on_sessions_updated(SessionManager.active_sessions)

func _on_connection_changed(online: bool) -> void:
    if online:
        conn_status_lbl.text = "Online"
        conn_dot.color = Color("#22C55E")
        connection_pill.add_theme_stylebox_override("panel", MobCodeTheme.panel_style(Color("#052E16"), Color("#166534"), 99, 8))
        setup_banner.visible = false
    else:
        conn_status_lbl.text = "Offline"
        conn_dot.color = Color("#EF4444")
        connection_pill.add_theme_stylebox_override("panel", MobCodeTheme.panel_style(Color("#2A0A0A"), Color("#7F1D1D"), 99, 8))
        setup_banner.visible = true

func _on_sessions_updated(sessions: Array) -> void:
    sessions_count_label.text = str(sessions.size())
    _push_session_summary(sessions)

    # Clear old cards
    for child in sessions_container.get_children():
        child.queue_free()

    if sessions.size() == 0:
        empty_state_lbl.visible = true
        sessions_scroll.visible = false
    else:
        empty_state_lbl.visible = false
        sessions_scroll.visible = true

        for s_data in sessions:
            var card = session_card_scene.instantiate()
            sessions_container.add_child(card)
            card.setup(s_data)
            card.session_selected.connect(open_terminal_view)

func _push_session_summary(sessions: Array) -> void:
    if terminal_plugin == null or not terminal_plugin.has_method("setSessionSummary"):
        return
    var parts := PackedStringArray()
    for i in range(min(sessions.size(), 3)):
        var data: Dictionary = sessions[i]
        var agent := str(data.get("agent", data.get("type", "shell")))
        var status := str(data.get("status", "running"))
        parts.append("%s · %s" % [AgentRegistry.display_name(agent), AgentRegistry.status_label(status)])
    var summary := ", ".join(parts)
    if sessions.size() > 3:
        summary += " +%d more" % (sessions.size() - 3)
    terminal_plugin.setSessionSummary(sessions.size(), summary)

func _on_copy_setup_pressed() -> void:
    TermuxBridge.copy_setup_command()
    copy_setup_btn.text = "Copied"
    var tween = create_tween()
    tween.tween_interval(2.0)
    tween.tween_callback(func(): copy_setup_btn.text = "Copy setup")
