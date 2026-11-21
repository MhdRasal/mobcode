class_name AgentRegistry
extends RefCounted

const ICON_OPENCODE: Texture2D = preload("res://assets/brands/opencode.svg")
const ICON_CLAUDE: Texture2D = preload("res://assets/brands/claude.svg")
const ICON_ANTIGRAVITY: Texture2D = preload("res://assets/brands/antigravity.svg")
const ICON_SHELL: Texture2D = preload("res://assets/icons/terminal.svg")
const ICON_MOBCODE: Texture2D = preload("res://assets/brands/mobcode.svg")

const ACCENT_OPENCODE := Color("#A78BFA")
const ACCENT_CLAUDE := Color("#D97757")
const ACCENT_ANTIGRAVITY := Color("#3186FF")
const ACCENT_SHELL := Color("#22C55E")
const ACCENT_PENDING := Color("#F59E0B")
const ACCENT_ERROR := Color("#EF4444")
const ACCENT_MUTED := Color("#71717A")

static func icon_for(agent_type: String) -> Texture2D:
	match agent_type:
		"opencode":
			return ICON_OPENCODE
		"claude":
			return ICON_CLAUDE
		"agy":
			return ICON_ANTIGRAVITY
		_:
			return ICON_SHELL

static func accent_for(agent_type: String) -> Color:
	match agent_type:
		"opencode":
			return ACCENT_OPENCODE
		"claude":
			return ACCENT_CLAUDE
		"agy":
			return ACCENT_ANTIGRAVITY
		_:
			return ACCENT_SHELL

static func display_name(agent_type: String) -> String:
	match agent_type:
		"opencode":
			return "OpenCode"
		"claude":
			return "Claude"
		"agy":
			return "Antigravity"
		_:
			return "Shell"

static func type_label(agent_type: String) -> String:
	match agent_type:
		"opencode":
			return "OPENCODE"
		"claude":
			return "CLAUDE"
		"agy":
			return "ANTIGRAVITY"
		_:
			return "SHELL"

static func subtitle(agent_type: String) -> String:
	match agent_type:
		"opencode":
			return "Open coding agent"
		"claude":
			return "Anthropic coding agent"
		"agy":
			return "Google Antigravity"
		_:
			return "Local terminal"

static func status_color(status: String) -> Color:
	match status:
		"running", "working", "active":
			return Color("#22C55E") # Green
		"needs_attention", "waiting", "input":
			return Color("#F59E0B") # Amber
		"completed", "done":
			return Color("#3B82F6") # Blue
		"stopped":
			return Color("#F97316") # Orange
		"paused":
			return Color("#8B5CF6") # Purple
		"error", "failed":
			return Color("#EF4444") # Red
		"pending", "starting":
			return Color("#F59E0B") # Amber
		_:
			return ACCENT_MUTED # Gray

static func status_label(status: String) -> String:
	match status:
		"running", "working", "active":
			return "Working"
		"needs_attention", "waiting", "input":
			return "Needs Approval"
		"completed", "done":
			return "Completed"
		"stopped":
			return "Stopped"
		"paused":
			return "Paused"
		"error", "failed":
			return "Error"
		"pending", "starting":
			return "Starting"
		_:
			return "Idle"
			return "Error"
		"pending", "starting":
			return "Starting"
		_:
			return "Idle"

static func status_short_label(status: String) -> String:
	match status:
		"running", "working", "active":
			return "Working"
		"needs_attention", "waiting", "input":
			return "Input"
		"completed", "done", "stopped":
			return "Done"
		"paused":
			return "Paused"
		"error", "failed":
			return "Error"
		"pending", "starting":
			return "Starting"
		_:
			return "Idle"
