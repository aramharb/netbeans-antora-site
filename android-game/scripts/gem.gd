extends Node2D
## A single gem. Drawn procedurally (no image assets) so it stays crisp at any
## resolution. Each colour also has a distinct shape for colour-blind players.

const COLORS := [
	Color("ff4d6d"), # red    - circle
	Color("ffd23f"), # yellow - rounded square
	Color("3ddc97"), # green  - diamond
	Color("4cc9f0"), # blue   - hexagon
	Color("9b6dff"), # violet - triangle
	Color("ff8c42"), # orange - star
]

var kind := 0
var radius := 32.0
var _select_tween: Tween

func set_kind(k: int) -> void:
	kind = k
	queue_redraw()

func get_color() -> Color:
	return COLORS[kind]

func set_selected(on: bool) -> void:
	if _select_tween:
		_select_tween.kill()
	_select_tween = create_tween()
	_select_tween.tween_property(self, "scale", Vector2.ONE * (1.18 if on else 1.0), 0.12) \
		.set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

func _draw() -> void:
	var col: Color = COLORS[kind]
	var pts := _shape(kind, radius)
	draw_colored_polygon(_xf(pts, 1.0, Vector2(0, 5)), Color(0, 0, 0, 0.30))
	draw_colored_polygon(pts, col.darkened(0.28))
	draw_colored_polygon(_xf(pts, 0.84, Vector2(-1.5, -2.0)), col)
	var shine := col.lightened(0.5)
	shine.a = 0.5
	draw_colored_polygon(_xf(pts, 0.48, Vector2(-5, -8)), shine)
	draw_circle(Vector2(-radius * 0.32, -radius * 0.38), radius * 0.11, Color(1, 1, 1, 0.85))
	var outline := pts.duplicate()
	outline.append(pts[0])
	draw_polyline(outline, Color(1, 1, 1, 0.35), 2.0, true)

static func _xf(pts: PackedVector2Array, s: float, off: Vector2) -> PackedVector2Array:
	var out := PackedVector2Array()
	for p in pts:
		out.append(p * s + off)
	return out

static func _ngon(n: int, r: float, rot: float) -> PackedVector2Array:
	var out := PackedVector2Array()
	for i in n:
		var a := rot + TAU * i / n
		out.append(Vector2(cos(a), sin(a)) * r)
	return out

static func _shape(k: int, r: float) -> PackedVector2Array:
	match k:
		0:
			return _ngon(40, r, 0.0)
		1:
			var out := PackedVector2Array()
			for i in 32:
				var a := TAU * i / 32.0
				var c := cos(a)
				var s := sin(a)
				out.append(Vector2(signf(c) * pow(absf(c), 0.5), signf(s) * pow(absf(s), 0.5)) * r * 0.95)
			return out
		2:
			return _ngon(4, r * 1.12, -PI / 2.0)
		3:
			return _ngon(6, r * 1.02, 0.0)
		4:
			return _ngon(3, r * 1.18, -PI / 2.0)
		_:
			var out := PackedVector2Array()
			for i in 10:
				var a := -PI / 2.0 + TAU * i / 10.0
				var rr := r * 1.12 if i % 2 == 0 else r * 0.52
				out.append(Vector2(cos(a), sin(a)) * rr)
			return out
