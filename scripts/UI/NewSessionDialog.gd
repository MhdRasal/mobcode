# ==============================================================================
# MobCode: New Session Creation Dialog
# ==============================================================================
class_name NewSessionDialog
extends ConfirmationDialog

signal session_created(session_id: String, agent_type: String)

@onready var name_input: LineEdit = $VBox/NameInput
@onready var type_label: Label = $VBox/AgentRow/AgentText/TypeLabel
@onready var agent_icon: TextureRect = $VBox/AgentRow/AgentIcon

var selected_agent_type: String = "agy"

func _ready() -> void:
	theme = MobCodeTheme.create()
	title = "New coding session"
	confirmed.connect(_on_confirmed)

func prompt_new_session(agent_type: String) -> void:
	selected_agent_type = agent_type
	var rand_suffix := randi() % 900 + 100
	var default_name := "%s-%d" % [agent_type, rand_suffix]
	var accent := AgentRegistry.accent_for(agent_type)

	if type_label:
		type_label.text = AgentRegistry.display_name(agent_type)
		type_label.add_theme_color_override("font_color", accent)
	if agent_icon:
		agent_icon.texture = AgentRegistry.icon_for(agent_type)
	if name_input:
		name_input.text = default_name
		name_input.select_all()

	popup_centered(Vector2i(360, 220))
	if name_input:
		name_input.grab_focus()

func _on_confirmed() -> void:
	var final_name := name_input.text.strip_edges()
	if final_name.is_empty():
		final_name = "%s-%d" % [selected_agent_type, randi() % 900 + 100]

	final_name = final_name.replace(" ", "-").replace(":", "-").to_lower()
	session_created.emit(final_name, selected_agent_type)
