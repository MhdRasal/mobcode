# ==============================================================================
# MobCode: Voice / Microphone Capture
# Captures audio buffer via AudioEffectCapture and provides speech-to-prompt dispatch
# ==============================================================================
class_name VoiceCapture
extends Node

signal recording_started
signal recording_stopped
signal prompt_ready(prompt_text: String)

var is_recording: bool = false
var capture_effect: AudioEffectCapture
var recorded_samples: PackedVector2Array = PackedVector2Array()

func _ready() -> void:
	_request_permissions()
	_setup_audio_bus()

func _request_permissions() -> void:
	if OS.get_name() == "Android":
		var permissions = OS.get_granted_permissions()
		if not permissions.has("android.permission.RECORD_AUDIO"):
			OS.request_permissions()

func _setup_audio_bus() -> void:
	var record_bus_idx = AudioServer.get_bus_index("Record")
	if record_bus_idx != -1:
		for i in range(AudioServer.get_bus_effect_count(record_bus_idx)):
			var effect = AudioServer.get_bus_effect(record_bus_idx, i)
			if effect is AudioEffectCapture:
				capture_effect = effect
				break
	else:
		# Create Record bus programmatically if missing
		AudioServer.add_bus(AudioServer.bus_count)
		record_bus_idx = AudioServer.bus_count - 1
		AudioServer.set_bus_name(record_bus_idx, "Record")
		capture_effect = AudioEffectCapture.new()
		AudioServer.add_bus_effect(record_bus_idx, capture_effect)

func start_recording() -> void:
	if not is_recording:
		is_recording = true
		recorded_samples.clear()
		if capture_effect:
			capture_effect.clear_buffer()
		recording_started.emit()

func stop_recording() -> void:
	if is_recording:
		is_recording = false
		recording_stopped.emit()
		_process_audio()

func _process(_delta: float) -> void:
	if is_recording and capture_effect:
		var frames = capture_effect.get_frames_available()
		if frames > 0:
			var buffer = capture_effect.get_buffer(frames)
			recorded_samples.append_array(buffer)

func _process_audio() -> void:
	if recorded_samples.size() == 0:
		return
		
	# Audio buffer captured.
	# Note: On mobile, this can be piped to Android SpeechRecognizer or an STT API.
	# For now, we signal prompt_ready with a status notification or transcription hook:
	var simulated_prompt = "[Voice Command: %d samples recorded]" % recorded_samples.size()
	prompt_ready.emit(simulated_prompt)
