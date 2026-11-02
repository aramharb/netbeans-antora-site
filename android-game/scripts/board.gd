extends Node2D
## Match-3 board: input, swapping, matching, gravity, refills, combos.

signal score_changed(score: int)
signal combo_changed(combo: int)
signal moves_changed(moves: int)
signal game_over(score: int)

const COLS := 8
const ROWS := 8
const CELL := 80
const KINDS := 6
const START_MOVES := 30
const Gem := preload("res://scripts/gem.gd")

var grid: Array = []
var score := 0
var moves := START_MOVES
var busy := false
var selected := Vector2i(-1, -1)
var dragging := false
var drag_origin := Vector2.ZERO

func _ready() -> void:
	new_game()

func new_game() -> void:
	for child in get_children():
		child.queue_free()
	grid = []
	score = 0
	moves = START_MOVES
	busy = false
	dragging = false
	selected = Vector2i(-1, -1)
	for x in COLS:
		var col: Array = []
		col.resize(ROWS)
		grid.append(col)
	for x in COLS:
		for y in ROWS:
			grid[x][y] = _spawn(x, y, _kind_without_match(x, y), 0.0)
	score_changed.emit(score)
	moves_changed.emit(moves)
	combo_changed.emit(0)

func _draw() -> void:
	draw_rect(Rect2(-14, -14, COLS * CELL + 28, ROWS * CELL + 28), Color(0, 0, 0, 0.38))
	for x in COLS:
		for y in ROWS:
			var a := 0.08 if (x + y) % 2 == 0 else 0.035
			draw_rect(Rect2(x * CELL + 3, y * CELL + 3, CELL - 6, CELL - 6), Color(1, 1, 1, a))

# ---------- helpers ----------

func _cell_pos(x: int, y: int) -> Vector2:
	return Vector2(x * CELL + CELL / 2.0, y * CELL + CELL / 2.0)

func _in_bounds(c: Vector2i) -> bool:
	return c.x >= 0 and c.y >= 0 and c.x < COLS and c.y < ROWS

func _pos_to_cell(p: Vector2) -> Vector2i:
	return Vector2i(floori(p.x / CELL), floori(p.y / CELL))

func _kind_without_match(x: int, y: int) -> int:
	var options: Array = range(KINDS)
	if x >= 2 and grid[x - 1][y].kind == grid[x - 2][y].kind:
		options.erase(grid[x - 1][y].kind)
	if y >= 2 and grid[x][y - 1].kind == grid[x][y - 2].kind:
		options.erase(grid[x][y - 1].kind)
	return options.pick_random()

func _spawn(x: int, y: int, kind: int, drop: float):
	var g = Gem.new()
	g.radius = CELL * 0.4
	g.set_kind(kind)
	g.position = _cell_pos(x, y) - Vector2(0, drop)
	add_child(g)
	return g

# ---------- input ----------

func _unhandled_input(event: InputEvent) -> void:
	if busy or moves <= 0:
		return
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		if event.pressed:
			_on_press(get_local_mouse_position())
		else:
			dragging = false
	elif event is InputEventMouseMotion and dragging:
		_on_drag(get_local_mouse_position())

func _on_press(p: Vector2) -> void:
	var c := _pos_to_cell(p)
	if not _in_bounds(c):
		return
	if _in_bounds(selected) and (absi(selected.x - c.x) + absi(selected.y - c.y)) == 1:
		var a := selected
		_set_selected(Vector2i(-1, -1))
		_try_swap(a, c)
		return
	_set_selected(c)
	dragging = true
	drag_origin = p

func _on_drag(p: Vector2) -> void:
	if not _in_bounds(selected):
		return
	var d := p - drag_origin
	if d.length() < CELL * 0.35:
		return
	var dir := Vector2i(int(signf(d.x)), 0) if absf(d.x) > absf(d.y) else Vector2i(0, int(signf(d.y)))
	var target := selected + dir
	dragging = false
	var a := selected
	_set_selected(Vector2i(-1, -1))
	if _in_bounds(target):
		_try_swap(a, target)

func _set_selected(c: Vector2i) -> void:
	if _in_bounds(selected) and grid[selected.x][selected.y]:
		grid[selected.x][selected.y].set_selected(false)
	selected = c
	if _in_bounds(c) and grid[c.x][c.y]:
		grid[c.x][c.y].set_selected(true)

# ---------- game flow ----------

func _try_swap(a: Vector2i, b: Vector2i) -> void:
	busy = true
	await _swap(a, b)
	if _find_matches().is_empty():
		await _swap(a, b)  # invalid move: swap back
		busy = false
		return
	moves -= 1
	moves_changed.emit(moves)
	await _resolve()
	if moves <= 0:
		game_over.emit(score)
	elif not _has_moves():
		await _reshuffle()
	busy = false

func _swap(a: Vector2i, b: Vector2i) -> void:
	var ga = grid[a.x][a.y]
	var gb = grid[b.x][b.y]
	grid[a.x][a.y] = gb
	grid[b.x][b.y] = ga
	var t := create_tween().set_parallel(true)
	t.tween_property(ga, "position", _cell_pos(b.x, b.y), 0.18).set_trans(Tween.TRANS_QUAD)
	t.tween_property(gb, "position", _cell_pos(a.x, a.y), 0.18).set_trans(Tween.TRANS_QUAD)
	await t.finished

func _find_matches() -> Dictionary:
	var found := {}
	for y in ROWS:
		var run := 1
		for x in range(1, COLS + 1):
			if x < COLS and grid[x][y].kind == grid[x - 1][y].kind:
				run += 1
			else:
				if run >= 3:
					for i in run:
						found[Vector2i(x - 1 - i, y)] = true
				run = 1
	for x in COLS:
		var run := 1
		for y in range(1, ROWS + 1):
			if y < ROWS and grid[x][y].kind == grid[x][y - 1].kind:
				run += 1
			else:
				if run >= 3:
					for i in run:
						found[Vector2i(x, y - 1 - i)] = true
				run = 1
	return found

func _resolve() -> void:
	var combo := 0
	while true:
		var m := _find_matches()
		if m.is_empty():
			break
		combo += 1
		combo_changed.emit(combo)
		score += m.size() * 10 * combo
		score_changed.emit(score)
		Input.vibrate_handheld(mini(20 + combo * 15, 80))

		var pop := create_tween().set_parallel(true)
		var doomed: Array = []
		for cell in m:
			var g = grid[cell.x][cell.y]
			grid[cell.x][cell.y] = null
			doomed.append(g)
			_burst(g.position, g.get_color())
			pop.tween_property(g, "scale", Vector2.ZERO, 0.2).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_IN)
			pop.tween_property(g, "modulate:a", 0.0, 0.2)
		await pop.finished
		for g in doomed:
			g.queue_free()
		await _collapse()
	combo_changed.emit(0)

func _collapse() -> void:
	var t := create_tween().set_parallel(true)
	var any := false
	for x in COLS:
		var write := ROWS - 1
		for y in range(ROWS - 1, -1, -1):
			var g = grid[x][y]
			if g != null:
				if y != write:
					grid[x][y] = null
					grid[x][write] = g
					t.tween_property(g, "position", _cell_pos(x, write), 0.38) \
						.set_trans(Tween.TRANS_BOUNCE).set_ease(Tween.EASE_OUT)
					any = true
				write -= 1
		var missing := write + 1
		for y in range(write, -1, -1):
			var ng = _spawn(x, y, randi() % KINDS, missing * CELL + CELL)
			ng.modulate.a = 0.0
			grid[x][y] = ng
			t.tween_property(ng, "position", _cell_pos(x, y), 0.45) \
				.set_trans(Tween.TRANS_BOUNCE).set_ease(Tween.EASE_OUT)
			t.tween_property(ng, "modulate:a", 1.0, 0.15)
			any = true
	if any:
		await t.finished

func _has_moves() -> bool:
	for x in COLS:
		for y in ROWS:
			for d in [Vector2i(1, 0), Vector2i(0, 1)]:
				var o: Vector2i = Vector2i(x, y) + d
				if not _in_bounds(o):
					continue
				var a = grid[x][y]
				var b = grid[o.x][o.y]
				grid[x][y] = b
				grid[o.x][o.y] = a
				var ok := not _find_matches().is_empty()
				grid[x][y] = a
				grid[o.x][o.y] = b
				if ok:
					return true
	return false

func _reshuffle() -> void:
	for attempt in 100:
		for x in COLS:
			for y in ROWS:
				grid[x][y].set_kind(randi() % KINDS)
		if _find_matches().is_empty() and _has_moves():
			break
	var t := create_tween().set_parallel(true)
	for x in COLS:
		for y in ROWS:
			var g = grid[x][y]
			g.scale = Vector2.ZERO
			t.tween_property(g, "scale", Vector2.ONE, 0.35) \
				.set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	await t.finished

func _burst(pos: Vector2, color: Color) -> void:
	var p := CPUParticles2D.new()
	p.position = pos
	p.one_shot = true
	p.amount = 14
	p.lifetime = 0.6
	p.explosiveness = 1.0
	p.direction = Vector2.UP
	p.spread = 180.0
	p.initial_velocity_min = 120.0
	p.initial_velocity_max = 280.0
	p.gravity = Vector2(0, 520)
	p.scale_amount_min = 3.0
	p.scale_amount_max = 8.0
	p.color = color
	add_child(p)
	p.emitting = true
	get_tree().create_timer(1.0).timeout.connect(p.queue_free)
