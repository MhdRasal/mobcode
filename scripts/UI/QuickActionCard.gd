class_name QuickActionCard
extends Button

@export var agent_type: String = "shell"

@onready var icon_rect: TextureRect = $Content/Icon
@onready var name_label: Label = $Content/TextBox/NameLabel
@onready var subtitle_label: Label = $Content/TextBox/SubtitleLabel
@onready var arrow_rect: TextureRect = $Content/Arrow

func _ready() -> void:
	text = ""
	focus_mode = Control.FOCUS_ALL
	icon_rect.texture = AgentRegistry.icon_for(agent_type)
	name_label.text = AgentRegistry.display_name(agent_type)
	subtitle_label.text = AgentRegistry.subtitle(agent_type)
	name_label.add_theme_color_override("font_color", MobCodeTheme.TEXT)
	subtitle_label.add_theme_color_override("font_color", MobCodeTheme.MUTED)
	arrow_rect.modulate = MobCodeTheme.MUTED_DIM
	arrow_rect.texture = preload("res://assets/icons/chevron-right.svg")

	var accent := AgentRegistry.accent_for(agent_type)
	add_theme_stylebox_override("normal", MobCodeTheme.card_style(accent))
	add_theme_stylebox_override("hover", MobCodeTheme.card_style(accent, true))
	add_theme_stylebox_override("pressed", MobCodeTheme.card_style(accent, true))
	add_theme_stylebox_override("focus", MobCodeTheme.panel_style(Color.TRANSPARENT, accent, 14, 14, 2))
