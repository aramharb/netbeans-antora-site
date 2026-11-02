extends Node2D
## Main scene: animated background, HUD, game-over screen, best-score saving.

const Board := preload("res://scripts/board.gd")
const SAVE_PATH := "user://save.cfg"

var board
var score_label: Label
var moves_label: Label
var best_label: Label
var combo_label: Label
var over_panel: ColorRect
var over_label: Label
var best := 0
var shown_score := 0.0
var _t := 0.0

func _ready() -> void:
	_load_best()
	_build_ui()
	board = Board.new()
	board.position = Vector2(40, 380)
	board.score_changed.connect(_on_score)
	board.moves_changed.connect(func(m): moves_label.text = "MOVES  %d" % m)
	board.combo_changed.connect(_on_combo)
	board.game_over.connect(_on_game_over)
	add_child(board)

func _process(delta: float) -> void:
	_t += delta
	queue_redraw()

func _draw() -> void:
	# vertical gradient far larger than the screen so any aspect ratio is covered
	var top := Color("2b1f5c")
	var bottom := Color("0b0720")
	draw_polygon(
		PackedVector2Array([Vector2(-1000, -1000), Vector2(1720, -1000), Vector2(1720, 2280), Vector2(-1000, 2280)]),
		PackedColorArray([top, top, bottom, bottom]))
	# soft drifting orbs
	for i in 7:
		var ph := _t * 0.25 + i * 1.7
		var c := Vector2(360 + sin(ph) * 300, 200 + i * 170 + cos(ph * 1.3) * 40)
		var col := Gem_color(i)
		col.a = 0.07
		draw_circle(c, 90 + 25 * sin(ph * 2.0), col)

func Gem_color(i: int) -> Color:
	return Board.Gem.COLORS[i % Board.Gem.COLORS.size()]

# ---------- UI ----------

func _label(text: String, size: int, pos: Vector2, width: float, color := Color.WHITE) -> Label:
	var l := Label.new()
	l.text = text
	l.position = pos
	l.size = Vector2(width, size * 1.4)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.add_theme_color_override("font_shadow_color", Color(0, 0, 0, 0.45))
	l.add_theme_constant_override("shadow_offset_y", 3)
	return l

func _build_ui() -> void:
	var ui := CanvasLayer.new()
	add_child(ui)
	ui.add_child(_label("GEM POP", 64, Vector2(0, 50), 720, Color("ffd23f")))
	ui.add_child(_label("SCORE", 28, Vector2(0, 150), 720, Color(1, 1, 1, 0.6)))
	score_label = _label("0", 96, Vector2(0, 180), 720)
	ui.add_child(score_label)
	moves_label = _label("MOVES  %d" % Board.START_MOVES, 38, Vector2(40, 310), 330, Color("4cc9f0"))
	ui.add_child(moves_label)
	best_label = _label("BEST  %d" % best, 38, Vector2(350, 310), 330, Color("3ddc97"))
	ui.add_child(best_label)
	combo_label = _label("", 64, Vector2(0, 1070), 720, Color("ff8c42"))
	combo_label.pivot_offset = Vector2(360, 45)
	ui.add_child(combo_label)

	over_panel = ColorRect.new()
	over_panel.color = Color(0, 0, 0, 0.7)
	over_panel.set_anchors_preset(Control.PRESET_FULL_RECT)
	over_panel.visible = false
	ui.add_child(over_panel)
	var box := VBoxContainer.new()
	box.set_anchors_preset(Control.PRESET_FULL_RECT)
	box.alignment = BoxContainer.ALIGNMENT_CENTER
	box.add_theme_constant_override("separation", 40)
	over_panel.add_child(box)
	over_label = Label.new()
	over_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	over_label.add_theme_font_size_override("font_size", 56)
	box.add_child(over_label)
	var btn := Button.new()
	btn.text = "PLAY AGAIN"
	btn.custom_minimum_size = Vector2(380, 120)
	btn.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	btn.add_theme_font_size_override("font_size", 44)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color("9b6dff")
	sb.set_corner_radius_all(28)
	sb.shadow_size = 12
	sb.shadow_color = Color(0, 0, 0, 0.4)
	for s in ["normal", "hover", "pressed", "focus"]:
		btn.add_theme_stylebox_override(s, sb)
	btn.pressed.connect(_restart)
	box.add_child(btn)

func _restart() -> void:
	over_panel.visible = false
	shown_score = 0
	board.new_game()

# ---------- signals ----------

func _on_score(s: int) -> void:
	var tw := create_tween()
	tw.tween_method(func(v: float): score_label.text = str(int(v)), shown_score, float(s), 0.35)
	shown_score = float(s)

func _on_combo(c: int) -> void:
	if c < 2:
		combo_label.text = ""
		return
	combo_label.text = "COMBO x%d!" % c
	combo_label.scale = Vector2(0.6, 0.6)
	create_tween().tween_property(combo_label, "scale", Vector2.ONE, 0.25) \
		.set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

func _on_game_over(s: int) -> void:
	var new_best := s > best
	if new_best:
		best = s
		_save_best()
		best_label.text = "BEST  %d" % best
	over_label.text = "GAME OVER\nScore %d\n%s" % [s, "New best!" if new_best else "Best %d" % best]
	over_panel.modulate.a = 0.0
	over_panel.visible = true
	create_tween().tween_property(over_panel, "modulate:a", 1.0, 0.3)

func _load_best() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(SAVE_PATH) == OK:
		best = int(cfg.get_value("game", "best", 0))

func _save_best() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("game", "best", best)
	cfg.save(SAVE_PATH)
