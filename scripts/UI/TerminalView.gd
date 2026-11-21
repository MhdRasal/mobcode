# ==============================================================================
# MobCode: Dedicated Live Terminal View (Full Screen, 1 Mic Button, Shortcuts)
# ==============================================================================
class_name TerminalView
extends Control

signal back_pressed

@onready var back_btn: Button = $VBox/TopBar/BackButton
@onready var session_title_lbl: Label = $VBox/TopBar/SessionTitleLabel
@onready var status_dot: ColorRect = $VBox/TopBar/StatusDot
@onready var mic_btn: Button = $VBox/TopBar/MicButton
@onready var pause_btn: Button = $VBox/TopBar/PauseButton
@onready var termux_btn: Button = $VBox/TopBar/TermuxButton
@onready var stop_btn: Button = $VBox/TopBar/StopButton

# Live Terminal Display (Maximized space)
@onready var terminal_text: RichTextLabel = $VBox/TerminalPanel/TerminalContainer/TerminalText

# In-Terminal Inline Prompt Line
@onready var prompt_line: HBoxContainer = $VBox/TerminalPanel/TerminalContainer/PromptLine
@onready var prompt_label: Label = $VBox/TerminalPanel/TerminalContainer/PromptLine/PromptLabel
@onready var command_input: LineEdit = $VBox/TerminalPanel/TerminalContainer/PromptLine/CommandInput
@onready var send_btn: Button = $VBox/TerminalPanel/TerminalContainer/PromptLine/SendButton

# Mobile Shortcuts Bar
@onready var btn_ctrl_c: Button = $VBox/ShortcutsBar/CtrlCButton
@onready var btn_esc: Button = $VBox/ShortcutsBar/EscButton
@onready var btn_tab: Button = $VBox/ShortcutsBar/TabButton
@onready var btn_enter: Button = $VBox/ShortcutsBar/EnterButton
@onready var btn_ctrl_z: Button = $VBox/ShortcutsBar/CtrlZButton
@onready var btn_up: Button = $VBox/ShortcutsBar/UpButton
@onready var btn_down: Button = $VBox/ShortcutsBar/DownButton

var active_session_id: String = ""
var voice_capture: VoiceCapture
var terminal_plugin = null
const ICON_PAUSE: Texture2D = preload("res://assets/icons/pause.svg")
const ICON_PLAY: Texture2D = preload("res://assets/icons/play.svg")
var _terminal_dragging := false
var _terminal_scrolled := false


func _ready() -> void:
    if Engine.has_singleton("MobCodeTerminal"):
        terminal_plugin = Engine.get_singleton("MobCodeTerminal")
        if terminal_plugin.has_signal("terminal_closed"):
            terminal_plugin.terminal_closed.connect(func(): back_pressed.emit())

    # Setup Voice Capture
    voice_capture = VoiceCapture.new()
    add_child(voice_capture)
    voice_capture.prompt_ready.connect(_on_voice_prompt_ready)

    # Top bar buttons
    back_btn.pressed.connect(func(): back_pressed.emit())
    pause_btn.pressed.connect(_on_pause_pressed)
    termux_btn.pressed.connect(_on_termux_pressed)
    stop_btn.pressed.connect(_on_stop_pressed)

    # 1-Tap Hold-to-Talk Mic button
    mic_btn.button_down.connect(_on_mic_down)
    mic_btn.button_up.connect(_on_mic_up)

    # Mobile shortcuts
    btn_ctrl_c.pressed.connect(func(): SessionManager.send_key(active_session_id, "ctrl_c"))
    btn_esc.pressed.connect(func(): SessionManager.send_key(active_session_id, "esc"))
    btn_tab.pressed.connect(func(): SessionManager.send_key(active_session_id, "tab"))
    btn_enter.pressed.connect(func(): SessionManager.send_key(active_session_id, "enter"))
    btn_ctrl_z.pressed.connect(func(): SessionManager.send_key(active_session_id, "ctrl_z"))
    btn_up.pressed.connect(func(): SessionManager.send_key(active_session_id, "up"))
    btn_down.pressed.connect(func(): SessionManager.send_key(active_session_id, "down"))

    # Mobile input bar
    command_input.text_submitted.connect(_on_command_submitted)
    send_btn.pressed.connect(_on_send_pressed)
    terminal_text.gui_input.connect(_on_terminal_gui_input)

    # Connect live streaming
    SessionManager.live_pane_updated.connect(_on_live_pane_updated)
    SessionManager.sessions_updated.connect(_on_sessions_updated)

    _apply_safe_area_insets()
    get_viewport().size_changed.connect(_apply_safe_area_insets)
    call_deferred("_apply_safe_area_insets")

func _apply_safe_area_insets() -> void:
    SafeArea.apply_to($VBox, 12.0, 10.0)

func open_session(session_id: String) -> void:
    active_session_id = session_id
    if terminal_plugin:
        var agent_type = "shell"
        if session_id.begins_with("agy"): agent_type = "agy"
        elif session_id.begins_with("opencode"): agent_type = "opencode"
        elif session_id.begins_with("claude"): agent_type = "claude"
        terminal_plugin.openTerminal(session_id, agent_type)
        return

    session_title_lbl.text = session_id
    if SessionManager.current_selected_session != session_id:
        SessionManager.select_session(session_id)
    terminal_text.text = "Session: " + session_id + "\nConnecting to live output stream..."

    # Configure prompt label based on session type
    if session_id.begins_with("agy"):
        prompt_label.text = "agy> "
        prompt_label.modulate = AgentRegistry.ACCENT_ANTIGRAVITY
        command_input.placeholder_text = "Prompt Antigravity..."
    elif session_id.begins_with("opencode"):
        prompt_label.text = "opencode> "
        prompt_label.modulate = AgentRegistry.ACCENT_OPENCODE
        command_input.placeholder_text = "Prompt OpenCode..."
    elif session_id.begins_with("claude"):
        prompt_label.text = "claude> "
        prompt_label.modulate = AgentRegistry.ACCENT_CLAUDE
        command_input.placeholder_text = "Prompt Claude..."
    else:
        prompt_label.text = "root# "
        prompt_label.modulate = AgentRegistry.ACCENT_SHELL
        command_input.placeholder_text = "type bash command..."

    command_input.grab_focus()
    _update_status()

func _update_status() -> void:
    var status = "idle"
    for s in SessionManager.active_sessions:
        if s["id"] == active_session_id:
            status = s.get("status", "idle")
            break

    status_dot.color = AgentRegistry.status_color(status)
    pause_btn.icon = ICON_PLAY if status == "paused" else ICON_PAUSE

func _on_pause_pressed() -> void:
    if active_session_id != "":
        SessionManager.toggle_pause(active_session_id)
        _update_status()

func _on_termux_pressed() -> void:
    if active_session_id != "":
        TermuxBridge.open_in_termux(active_session_id)

func _on_stop_pressed() -> void:
    if active_session_id != "":
        SessionManager.stop_session(active_session_id)
        back_pressed.emit()

func _on_mic_down() -> void:
    mic_btn.modulate = Color("#e74c3c")
    voice_capture.start_recording()

func _on_mic_up() -> void:
    mic_btn.modulate = Color.WHITE
    voice_capture.stop_recording()

func _on_voice_prompt_ready(text: String) -> void:
    if active_session_id != "":
        DebugLogger.log_msg("TerminalView", "Voice command sent: " + text)
        if terminal_plugin:
            terminal_plugin.sendVoiceText(text + " ")
        else:
            SessionManager.send_prompt_to_session(text, active_session_id)

func _on_live_pane_updated(text: String) -> void:
    if visible and not text.is_empty():
        var bar = _get_terminal_scroll_bar()
        var at_bottom = true
        var old_value = 0.0
        if bar != null:
            old_value = bar.value
            at_bottom = bar.value >= bar.max_value - bar.page - 8
        terminal_text.text = text
        if at_bottom:
            terminal_text.scroll_following = true
            if terminal_text.has_method("scroll_to_line") and terminal_text.has_method("get_line_count"):
                terminal_text.scroll_to_line(maxi(0, terminal_text.get_line_count() - 1))
        elif bar != null:
            bar.value = old_value

func _on_sessions_updated(_sessions: Array) -> void:
    if visible:
        _update_status()

func _on_command_submitted(text: String) -> void:
    _dispatch_input_text(text)

func _on_send_pressed() -> void:
    _dispatch_input_text(command_input.text)

func _dispatch_input_text(text: String) -> void:
    if active_session_id == "":
        return
    if text.is_empty():
        SessionManager.send_key(active_session_id, "enter")
    else:
        SessionManager.send_prompt_to_session(text, active_session_id)
        command_input.clear()

func _on_terminal_gui_input(event: InputEvent) -> void:
    if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
        if event.pressed:
            _terminal_dragging = true
            _terminal_scrolled = false
        elif _terminal_dragging:
            _terminal_dragging = false
            if not _terminal_scrolled:
                if command_input.has_focus():
                    command_input.release_focus()
                    DisplayServer.virtual_keyboard_hide()
                else:
                    command_input.grab_focus()
                    DisplayServer.virtual_keyboard_show(command_input.text)
    elif event is InputEventMouseMotion and _terminal_dragging:
        if absf(event.relative.y) > 0.5:
            var bar = _get_terminal_scroll_bar()
            if bar != null:
                bar.value -= event.relative.y
                _terminal_scrolled = true
                terminal_text.scroll_following = false

func _get_terminal_scroll_bar():
    if terminal_text.has_method("get_v_scroll_bar"):
        return terminal_text.get_v_scroll_bar()
    return null
