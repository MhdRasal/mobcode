class_name SafeArea
extends RefCounted

static func get_insets(viewport_size: Vector2) -> Dictionary:
	var native := _native_insets()
	if not native.is_empty():
		return native
	return _display_safe_area_insets(viewport_size)

static func apply_to(control: Control, base_padding: float, extra: float = 8.0) -> void:
	if control == null:
		return
	var viewport := control.get_viewport()
	var viewport_size := viewport.get_visible_rect().size if viewport != null else Vector2.ZERO
	var insets := get_insets(viewport_size)
	control.offset_left = maxf(base_padding, float(insets.get("left", 0.0)) + extra)
	control.offset_top = maxf(base_padding, float(insets.get("top", 0.0)) + extra)
	control.offset_right = minf(-base_padding, -(float(insets.get("right", 0.0)) + extra))
	control.offset_bottom = minf(-base_padding, -(float(insets.get("bottom", 0.0)) + extra))

static func _native_insets() -> Dictionary:
	if not Engine.has_singleton("MobCodeTerminal"):
		return {}
	var plugin = Engine.get_singleton("MobCodeTerminal")
	if plugin == null or not plugin.has_method("getSafeAreaInsets"):
		return {}
	var raw = plugin.getSafeAreaInsets()
	if typeof(raw) != TYPE_STRING or raw.is_empty():
		return {}
	var parsed = JSON.parse_string(raw)
	if typeof(parsed) != TYPE_DICTIONARY:
		return {}
	return {
		"top": float(parsed.get("top", 0)),
		"right": float(parsed.get("right", 0)),
		"bottom": float(parsed.get("bottom", 0)),
		"left": float(parsed.get("left", 0))
	}

static func _display_safe_area_insets(viewport_size: Vector2) -> Dictionary:
	var safe_area := DisplayServer.get_display_safe_area()
	var window_size := DisplayServer.window_get_size()
	var result := {"top": 0.0, "right": 0.0, "bottom": 0.0, "left": 0.0}
	if window_size.x <= 0 or window_size.y <= 0:
		return result
	var scale := Vector2(
		viewport_size.x / float(window_size.x),
		viewport_size.y / float(window_size.y)
	)
	result["top"] = maxf(0.0, safe_area.position.y) * scale.y
	result["left"] = maxf(0.0, safe_area.position.x) * scale.x
	result["right"] = maxf(0.0, window_size.x - safe_area.end.x) * scale.x
	result["bottom"] = maxf(0.0, window_size.y - safe_area.end.y) * scale.y
	return result
