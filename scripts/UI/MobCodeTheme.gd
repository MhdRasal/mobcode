class_name MobCodeTheme
extends RefCounted

const BG := Color("#09090B")
const SURFACE := Color("#111113")
const SURFACE_2 := Color("#18181B")
const SURFACE_3 := Color("#1F1F23")
const BORDER := Color("#27272A")
const BORDER_STRONG := Color("#3F3F46")
const TEXT := Color("#FAFAFA")
const MUTED := Color("#A1A1AA")
const MUTED_DIM := Color("#71717A")
const ACCENT := Color("#8B5CF6")
const ACCENT_SOFT := Color("#2E1065")

static func panel_style(bg: Color, border: Color, radius: int = 16, margin: int = 16, border_width: int = 1) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = bg
	style.border_color = border
	style.set_border_width_all(border_width)
	style.set_corner_radius_all(radius)
	style.set_content_margin_all(margin)
	style.anti_aliasing = true
	return style

static func card_style(accent: Color, hovered: bool = false) -> StyleBoxFlat:
	var bg := SURFACE_3 if hovered else SURFACE_2
	var border := accent if hovered else BORDER_STRONG
	var style := panel_style(bg, border, 14, 14)
	style.border_width_left = 3
	style.border_color = accent
	return style

static func pill_style(color: Color) -> StyleBoxFlat:
	var style := panel_style(Color(color.r, color.g, color.b, 0.14), Color(color.r, color.g, color.b, 0.45), 99, 6, 1)
	style.content_margin_left = 8
	style.content_margin_right = 8
	style.content_margin_top = 3
	style.content_margin_bottom = 3
	return style

static func create() -> Theme:
	var theme := Theme.new()
	theme.default_font_size = 14

	theme.set_color("font_color", "Label", TEXT)
	theme.set_color("font_shadow_color", "Label", Color.TRANSPARENT)
	theme.set_font_size("font_size", "Label", 14)

	theme.set_stylebox("panel", "PanelContainer", panel_style(SURFACE, BORDER, 18, 18))
	theme.set_stylebox("panel", "Panel", panel_style(SURFACE, BORDER, 18, 18))

	theme.set_color("font_color", "Button", TEXT)
	theme.set_color("font_hover_color", "Button", TEXT)
	theme.set_color("font_pressed_color", "Button", TEXT)
	theme.set_color("font_disabled_color", "Button", MUTED_DIM)
	theme.set_color("icon_normal_color", "Button", TEXT)
	theme.set_color("icon_hover_color", "Button", ACCENT)
	theme.set_color("icon_pressed_color", "Button", ACCENT)
	theme.set_color("icon_disabled_color", "Button", MUTED_DIM)
	theme.set_font_size("font_size", "Button", 14)
	theme.set_constant("icon_max_width", "Button", 20)
	theme.set_constant("h_separation", "Button", 8)
	theme.set_stylebox("normal", "Button", panel_style(SURFACE_2, BORDER, 12, 10))
	theme.set_stylebox("hover", "Button", panel_style(SURFACE_3, BORDER_STRONG, 12, 10))
	theme.set_stylebox("pressed", "Button", panel_style(ACCENT_SOFT, ACCENT, 12, 10))
	theme.set_stylebox("disabled", "Button", panel_style(SURFACE, BORDER, 12, 10))
	theme.set_stylebox("focus", "Button", panel_style(Color.TRANSPARENT, ACCENT, 12, 10, 2))

	theme.set_color("font_color", "LineEdit", TEXT)
	theme.set_color("font_placeholder_color", "LineEdit", MUTED_DIM)
	theme.set_color("caret_color", "LineEdit", ACCENT)
	theme.set_color("selection_color", "LineEdit", Color(0.55, 0.36, 0.96, 0.35))
	theme.set_font_size("font_size", "LineEdit", 14)
	theme.set_stylebox("normal", "LineEdit", panel_style(BG, BORDER, 12, 10))
	theme.set_stylebox("focus", "LineEdit", panel_style(BG, ACCENT, 12, 10, 2))
	theme.set_stylebox("read_only", "LineEdit", panel_style(SURFACE, BORDER, 12, 10))

	theme.set_color("default_color", "RichTextLabel", TEXT)
	theme.set_color("selection_color", "RichTextLabel", Color(0.55, 0.36, 0.96, 0.35))
	theme.set_font_size("normal_font_size", "RichTextLabel", 13)

	theme.set_stylebox("separator", "HSeparator", panel_style(BORDER, BORDER, 0, 0, 1))
	theme.set_constant("separation", "HSeparator", 1)

	var scroll_track := StyleBoxFlat.new()
	scroll_track.bg_color = Color.TRANSPARENT
	scroll_track.set_content_margin_all(0)
	var scroll_grabber := StyleBoxFlat.new()
	scroll_grabber.bg_color = BORDER_STRONG
	scroll_grabber.set_corner_radius_all(99)
	var scroll_grabber_hover := StyleBoxFlat.new()
	scroll_grabber_hover.bg_color = MUTED_DIM
	scroll_grabber_hover.set_corner_radius_all(99)
	for bar_type in ["VScrollBar", "HScrollBar"]:
		theme.set_stylebox("scroll", bar_type, scroll_track)
		theme.set_stylebox("scroll_focus", bar_type, scroll_track)
		theme.set_stylebox("grabber", bar_type, scroll_grabber)
		theme.set_stylebox("grabber_highlight", bar_type, scroll_grabber_hover)
		theme.set_stylebox("grabber_pressed", bar_type, scroll_grabber_hover)

	theme.set_constant("separation", "VBoxContainer", 10)
	theme.set_constant("separation", "HBoxContainer", 10)
	theme.set_constant("v_separation", "GridContainer", 10)
	theme.set_constant("h_separation", "GridContainer", 10)

	return theme
