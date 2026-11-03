package com.example.gempop

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Match-3 where every tile has a COLOR and a NUMBER.
 *
 *  - 3+ in a row/column with the same COLOR  -> match
 *  - 3+ in a row/column with the same NUMBER -> match
 *  - 3+ with the same COLOR *and* NUMBER     -> PERFECT match:
 *      clears the whole row + column of the match, +2 moves, big score bonus.
 */
class GameView(context: Context) : View(context) {

    private data class Pt(val x: Int, val y: Int)
    private class Tile(var color: Int, var num: Int, var x: Float, var y: Float) {
        var scale = 1f
        var alpha = 1f
        var special = 0 // 0 none, 1 bomb (clears 3x3), 2 rainbow (swap: clears a color)
    }
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Int)
    private class MatchResult(val cleared: MutableSet<Pt>, val totals: MutableSet<Pt>, val spawns: MutableMap<Pt, Int>)

    companion object {
        const val N = 8
        const val COLOR_COUNT = 5
        const val NUM_COUNT = 5
        const val START_MOVES = 25
        const val BOMB = 1
        const val RAINBOW = 2
        private val PALETTE = intArrayOf(
            Color.parseColor("#FF4D6D"), // red
            Color.parseColor("#FFD23F"), // yellow
            Color.parseColor("#3DDC97"), // green
            Color.parseColor("#4CC9F0"), // blue
            Color.parseColor("#9B6DFF"), // violet
        )
    }

    // ---- state ----
    private val grid = Array(N) { arrayOfNulls<Tile>(N) }
    private var score = 0
    private var moves = START_MOVES
    private var best = 0
    private var busy = false
    private var gameOver = false
    private var selected: Pt? = null
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val particles = ArrayList<Particle>()
    private var lastFrame = 0L
    private var message: String? = null
    private var messageAlpha = 0f
    private var messageScale = 1f
    private var flash = 0f
    private val sfx = SoundFx()
    private val soundRect = RectF()
    private val rainbowShader = android.graphics.SweepGradient(
        0f, 0f, PALETTE + PALETTE[0], null
    )
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val prefs = context.getSharedPreferences("gempop", Context.MODE_PRIVATE)

    // ---- layout ----
    private var unit = 1f
    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var bg: LinearGradient? = null
    private val restartRect = RectF()
    private var specialCount = 0

    // ---- paints / shapes ----
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val shapes: Array<Path> = Array(COLOR_COUNT) { shapePath(it) }
    private val starPath = Path().apply {
        for (i in 0 until 10) {
            val ang = -Math.PI / 2 + 2 * Math.PI * i / 10
            val rr = if (i % 2 == 0) 1f else 0.45f
            val px = (cos(ang) * rr).toFloat()
            val py = (sin(ang) * rr).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    init {
        best = prefs.getInt("best", 0)
        sfx.enabled = prefs.getBoolean("sound", true)
        newGame()
    }

    fun release() = sfx.release()

    // =====================================================================
    // Game logic
    // =====================================================================

    private fun newGame() {
        score = 0
        moves = START_MOVES
        busy = false
        gameOver = false
        selected = null
        particles.clear()
        message = null
        for (x in 0 until N) for (y in 0 until N) grid[x][y] = null
        for (y in 0 until N) for (x in 0 until N) {
            var c: Int
            var n: Int
            do {
                c = Random.nextInt(COLOR_COUNT)
                n = Random.nextInt(1, NUM_COUNT + 1)
            } while (createsRun(x, y, c, n))
            grid[x][y] = Tile(c, n, x.toFloat(), y.toFloat())
        }
        if (!hasMoves()) reshuffleInstant()
        invalidate()
    }

    /** True if placing (c,n) at (x,y) while filling left-to-right, top-to-bottom would form a run. */
    private fun createsRun(x: Int, y: Int, c: Int, n: Int): Boolean {
        if (x >= 2) {
            val a = grid[x - 1][y]!!
            val b = grid[x - 2][y]!!
            if ((a.color == c && b.color == c) || (a.num == n && b.num == n)) return true
        }
        if (y >= 2) {
            val a = grid[x][y - 1]!!
            val b = grid[x][y - 2]!!
            if ((a.color == c && b.color == c) || (a.num == n && b.num == n)) return true
        }
        return false
    }

    private fun findMatches(): MatchResult {
        val cleared = HashSet<Pt>()
        val totals = LinkedHashSet<Pt>()
        val spawns = HashMap<Pt, Int>()
        for (dir in 0..1) {
            for (line in 0 until N) {
                fun at(i: Int): Tile = if (dir == 0) grid[i][line]!! else grid[line][i]!!
                fun pt(i: Int): Pt = if (dir == 0) Pt(i, line) else Pt(line, i)
                for (mode in 0..1) { // 0 = by color, 1 = by number
                    fun key(t: Tile) = if (mode == 0) t.color else t.num
                    var start = 0
                    for (i in 1..N) {
                        val same = i < N && at(i).special != RAINBOW && at(i - 1).special != RAINBOW && key(at(i)) == key(at(i - 1))
                        if (same) continue
                        if (i - start >= 3) {
                            var total = true
                            for (j in start until i) {
                                if (at(j).color != at(start).color || at(j).num != at(start).num) total = false
                            }
                            for (j in start until i) cleared.add(pt(j))
                            if (total) totals.add(pt((start + i - 1) / 2))
                            if (i - start >= 4) {
                                val kind = if (i - start >= 5) RAINBOW else BOMB
                                val at = pt((start + i - 1) / 2)
                                if ((spawns[at] ?: 0) < kind) spawns[at] = kind
                            }
                        }
                        start = i
                    }
                }
            }
        }
        return MatchResult(cleared, totals, spawns)
    }

    private fun hasMoves(): Boolean {
        for (x in 0 until N) for (y in 0 until N) {
            for (d in arrayOf(Pt(1, 0), Pt(0, 1))) {
                val ox = x + d.x
                val oy = y + d.y
                if (ox >= N || oy >= N) continue
                val a = grid[x][y]
                grid[x][y] = grid[ox][oy]
                grid[ox][oy] = a
                val ok = a!!.special == RAINBOW || grid[x][y]!!.special == RAINBOW || findMatches().cleared.isNotEmpty()
                grid[ox][oy] = grid[x][y]
                grid[x][y] = a
                if (ok) return true
            }
        }
        return false
    }

    private fun reshuffleInstant() {
        var guard = 0
        do {
            for (x in 0 until N) for (y in 0 until N) {
                val t = grid[x][y]!!
                t.color = Random.nextInt(COLOR_COUNT)
                t.num = Random.nextInt(1, NUM_COUNT + 1)
                t.special = 0
            }
            guard++
        } while ((findMatches().cleared.isNotEmpty() || !hasMoves()) && guard < 200)
    }

    private fun trySwap(a: Pt, b: Pt) {
        busy = true
        sfx.play("swap")
        swap(a, b) {
            val ta = grid[a.x][a.y]!!
            val tb = grid[b.x][b.y]!!
            if (ta.special == RAINBOW || tb.special == RAINBOW) {
                moves--
                activateRainbow(a, b)
            } else if (findMatches().cleared.isEmpty()) {
                sfx.play("bad")
                swap(a, b) { busy = false } // invalid move: swap back
            } else {
                moves--
                resolve(1)
            }
        }
    }

    private fun swap(a: Pt, b: Pt, end: () -> Unit) {
        val ta = grid[a.x][a.y]!!
        val tb = grid[b.x][b.y]!!
        grid[a.x][a.y] = tb
        grid[b.x][b.y] = ta
        moveTiles(
            listOf(Triple(ta, b.x.toFloat(), b.y.toFloat()), Triple(tb, a.x.toFloat(), a.y.toFloat())),
            170, AccelerateDecelerateInterpolator(), end
        )
    }

    private fun resolve(combo: Int) {
        val m = findMatches()
        if (m.cleared.isEmpty()) {
            finishTurn()
            return
        }
        val toClear = HashSet<Pt>(m.cleared)
        for (t in m.totals) {
            for (i in 0 until N) {
                toClear.add(Pt(i, t.y))
                toClear.add(Pt(t.x, i))
            }
        }
        clearTiles(toClear, m.spawns, m.totals.size, combo, null)
    }

    /** Swapping a rainbow gem clears every tile of the neighbour's color (or the whole board). */
    private fun activateRainbow(a: Pt, b: Pt) {
        val ta = grid[a.x][a.y]!!
        val tb = grid[b.x][b.y]!!
        val toClear = HashSet<Pt>()
        toClear.add(a)
        toClear.add(b)
        val both = ta.special == RAINBOW && tb.special == RAINBOW
        val target = if (ta.special == RAINBOW) tb.color else ta.color
        for (x in 0 until N) for (y in 0 until N) {
            if (both || grid[x][y]!!.color == target) toClear.add(Pt(x, y))
        }
        clearTiles(toClear, emptyMap(), 0, 1, if (both) "RAINBOW BLAST!" else "RAINBOW!")
    }

    private fun clearTiles(toClear: MutableSet<Pt>, spawns: Map<Pt, Int>, perfects: Int, combo: Int, label: String?) {
        // bombs in the cleared set explode a 3x3 area (chains)
        var bombs = 0
        val exploded = HashSet<Pt>()
        var changed = true
        while (changed) {
            changed = false
            for (p in toClear.toList()) {
                if (p in exploded || grid[p.x][p.y]!!.special != BOMB) continue
                exploded.add(p)
                bombs++
                changed = true
                for (dx in -1..1) for (dy in -1..1) {
                    val q = Pt(p.x + dx, p.y + dy)
                    if (q.x in 0 until N && q.y in 0 until N) toClear.add(q)
                }
            }
        }
        // 4-match -> bomb, 5-match -> rainbow: that tile survives and is upgraded
        val survivors = spawns.filterKeys { it !in exploded }
        for (p in survivors.keys) toClear.remove(p)

        score += toClear.size * 10 * combo + perfects * 300 * combo + bombs * 50 * combo
        when {
            perfects > 0 -> {
                moves += 2 * perfects
                showMessage("PERFECT MATCH!\n+${2 * perfects} moves")
                flash = 1f
                animate(500, DecelerateInterpolator(), { flash = 1f - it }) {}
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                sfx.play("perfect")
            }
            label != null -> {
                showMessage(label)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                sfx.play("rainbow")
            }
            bombs > 0 -> {
                showMessage("BOOM!")
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                sfx.play("boom")
            }
            else -> {
                if (combo >= 2) showMessage("COMBO x$combo")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sfx.play("match${minOf(combo - 1, 5)}")
            }
        }
        val tiles = toClear.map { grid[it.x][it.y]!! }
        for (t in tiles) burst(t, if (perfects > 0 || bombs > 0 || label != null) 16 else 9)
        animate(220, AccelerateInterpolator(), { f -> tiles.forEach { it.scale = 1f - f; it.alpha = 1f - f } }) {
            for (p in toClear) grid[p.x][p.y] = null
            if (survivors.isNotEmpty()) {
                for ((p, kind) in survivors) grid[p.x][p.y]?.let { t ->
                    t.special = kind
                    t.scale = 0.5f
                    scaleTo(t, 1f)
                }
                sfx.play("spawn")
            }
            collapse { resolve(combo + 1) }
        }
    }

    private fun collapse(end: () -> Unit) {
        val targets = ArrayList<Triple<Tile, Float, Float>>()
        for (x in 0 until N) {
            var write = N - 1
            for (y in N - 1 downTo 0) {
                val t = grid[x][y] ?: continue
                if (y != write) {
                    grid[x][y] = null
                    grid[x][write] = t
                    targets.add(Triple(t, x.toFloat(), write.toFloat()))
                }
                write--
            }
            val missing = write + 1
            for (y in write downTo 0) {
                val t = Tile(Random.nextInt(COLOR_COUNT), Random.nextInt(1, NUM_COUNT + 1), x.toFloat(), (y - missing).toFloat())
                grid[x][y] = t
                targets.add(Triple(t, x.toFloat(), y.toFloat()))
            }
        }
        moveTiles(targets, 400, BounceInterpolator(), end)
    }

    private fun finishTurn() {
        if (moves <= 0) {
            gameOver = true
            sfx.play("over")
            if (score > best) {
                best = score
                prefs.edit().putInt("best", best).apply()
            }
            busy = false
            invalidate()
            return
        }
        if (!hasMoves()) {
            showMessage("No moves left\nShuffling...")
            sfx.play("shuffle")
            reshuffleInstant()
            val all = ArrayList<Tile>()
            for (x in 0 until N) for (y in 0 until N) all.add(grid[x][y]!!)
            all.forEach { it.scale = 0f }
            animate(350, OvershootInterpolator(), { f -> all.forEach { it.scale = f } }) { busy = false }
        } else {
            busy = false
        }
    }

    // =====================================================================
    // Animation helpers
    // =====================================================================

    private fun animate(ms: Long, interp: TimeInterpolator, update: (Float) -> Unit, end: () -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ms
        a.interpolator = interp
        a.addUpdateListener { update(it.animatedValue as Float); invalidate() }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = end()
        })
        a.start()
    }

    private fun moveTiles(targets: List<Triple<Tile, Float, Float>>, ms: Long, interp: TimeInterpolator, end: () -> Unit) {
        if (targets.isEmpty()) {
            end()
            return
        }
        val sx = targets.map { it.first.x }
        val sy = targets.map { it.first.y }
        animate(ms, interp, { f ->
            targets.forEachIndexed { i, (t, tx, ty) ->
                t.x = sx[i] + (tx - sx[i]) * f
                t.y = sy[i] + (ty - sy[i]) * f
            }
        }, end)
    }

    private fun showMessage(msg: String) {
        message = msg
        animate(1100, AccelerateDecelerateInterpolator(), { f ->
            messageAlpha = if (f < 0.6f) 1f else 1f - (f - 0.6f) / 0.4f
            messageScale = 0.7f + 0.3f * min(1f, f * 5f)
        }) { message = null }
    }

    private fun burst(t: Tile, count: Int) {
        val cx = boardLeft + (t.x + 0.5f) * cell
        val cy = boardTop + (t.y + 0.5f) * cell
        repeat(count) {
            val ang = Random.nextFloat() * (2 * Math.PI).toFloat()
            val sp = (120f + Random.nextFloat() * 300f) * unit
            particles.add(Particle(cx, cy, cos(ang) * sp, sin(ang) * sp - 120f * unit, 0.6f, PALETTE[t.color]))
        }
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    private fun select(p: Pt?) {
        selected?.let { s -> grid[s.x][s.y]?.let { t -> scaleTo(t, 1f) } }
        selected = p
        p?.let { s -> grid[s.x][s.y]?.let { t -> scaleTo(t, 1.15f) } }
    }

    private fun scaleTo(t: Tile, target: Float) {
        val from = t.scale
        animate(120, DecelerateInterpolator(), { f -> t.scale = from + (target - from) * f }) {}
    }

    // =====================================================================
    // Input
    // =====================================================================

    private fun cellAt(px: Float, py: Float): Pt? {
        val x = ((px - boardLeft) / cell).toInt()
        val y = ((py - boardTop) / cell).toInt()
        if (px < boardLeft || py < boardTop || x !in 0 until N || y !in 0 until N) return null
        return Pt(x, y)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (soundRect.contains(e.x, e.y)) {
                    sfx.enabled = !sfx.enabled
                    prefs.edit().putBoolean("sound", sfx.enabled).apply()
                    invalidate()
                    return true
                }
                if (gameOver) {
                    if (restartRect.contains(e.x, e.y)) newGame()
                    return true
                }
                if (busy) return true
                val c = cellAt(e.x, e.y) ?: return true
                val s = selected
                if (s != null && abs(s.x - c.x) + abs(s.y - c.y) == 1) {
                    select(null)
                    trySwap(s, c)
                    return true
                }
                select(c)
                downX = e.x
                downY = e.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected
                if (dragging && !busy && !gameOver && s != null) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (hypot(dx, dy) > cell * 0.35f) {
                        dragging = false
                        val target = if (abs(dx) > abs(dy)) Pt(s.x + sign(dx).toInt(), s.y) else Pt(s.x, s.y + sign(dy).toInt())
                        select(null)
                        if (target.x in 0 until N && target.y in 0 until N) trySwap(s, target)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = w / 720f
        val margin = 32f * unit
        val top = 400f * unit
        val bottomReserve = 230f * unit
        cell = min((w - 2 * margin) / N, (h - top - bottomReserve) / N)
        boardLeft = (w - cell * N) / 2f
        boardTop = top
        bg = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#2B1F5C"), Color.parseColor("#0B0720"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        fill.shader = bg
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        drawHud(c)

        // board panel
        val panel = RectF(boardLeft - 12 * unit, boardTop - 12 * unit, boardLeft + cell * N + 12 * unit, boardTop + cell * N + 12 * unit)
        fill.color = Color.argb(95, 0, 0, 0)
        c.drawRoundRect(panel, 24 * unit, 24 * unit, fill)
        for (x in 0 until N) for (y in 0 until N) {
            fill.color = Color.argb(if ((x + y) % 2 == 0) 20 else 9, 255, 255, 255)
            val r = RectF(boardLeft + x * cell + 3 * unit, boardTop + y * cell + 3 * unit, boardLeft + (x + 1) * cell - 3 * unit, boardTop + (y + 1) * cell - 3 * unit)
            c.drawRoundRect(r, 10 * unit, 10 * unit, fill)
        }

        // tiles (clipped so falling tiles don't draw over the HUD)
        c.save()
        c.clipRect(panel)
        specialCount = 0
        for (x in 0 until N) for (y in 0 until N) grid[x][y]?.let { drawTile(c, it) }
        c.restore()
        if (specialCount > 0) postInvalidateOnAnimation()

        drawParticles(c)

        if (flash > 0f) {
            fill.color = Color.argb((flash * 90).toInt(), 255, 255, 255)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
        drawMessage(c)
        if (gameOver) drawGameOver(c)
    }

    private fun drawHud(c: Canvas) {
        text.color = Color.parseColor("#FFD23F")
        text.textSize = 60 * unit
        c.drawText("GEM POP", width / 2f, 90 * unit, text)

        soundRect.set(width - 190 * unit, 30 * unit, width - 24 * unit, 100 * unit)
        fill.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(soundRect, 20 * unit, 20 * unit, fill)
        text.color = Color.WHITE
        text.textSize = 28 * unit
        c.drawText(if (sfx.enabled) "SOUND ON" else "SOUND OFF", soundRect.centerX(), soundRect.centerY() + 10 * unit, text)

        text.color = Color.argb(160, 255, 255, 255)
        text.textSize = 26 * unit
        c.drawText("SCORE", width / 2f, 150 * unit, text)
        text.color = Color.WHITE
        text.textSize = 92 * unit
        c.drawText(score.toString(), width / 2f, 235 * unit, text)

        text.textSize = 36 * unit
        text.color = Color.parseColor("#4CC9F0")
        c.drawText("MOVES  $moves", width * 0.27f, 300 * unit, text)
        text.color = Color.parseColor("#3DDC97")
        c.drawText("BEST  ${max(best, score)}", width * 0.73f, 300 * unit, text)

        text.textSize = 24 * unit
        text.color = Color.argb(200, 255, 255, 255)
        val y = boardTop + cell * N + 62 * unit
        c.drawText("Match 3+ by COLOR or by NUMBER", width / 2f, y, text)
        text.color = Color.parseColor("#FFD23F")
        c.drawText("Same color + number = PERFECT: row & column clear, +2 moves", width / 2f, y + 36 * unit, text)
        text.color = Color.argb(200, 255, 255, 255)
        c.drawText("Match 4 = BOMB (3x3)   Match 5 = RAINBOW (swap: clear a color)", width / 2f, y + 72 * unit, text)
    }

    private fun drawTile(c: Canvas, t: Tile) {
        val cx = boardLeft + (t.x + 0.5f) * cell
        val cy = boardTop + (t.y + 0.5f) * cell
        val r = cell * 0.40f * t.scale
        if (r < 1f) return
        val a = (t.alpha * 255).toInt().coerceIn(0, 255)
        val base = PALETTE[t.color]
        val shape = shapes[t.color]

        fun poly(scale: Float, dx: Float, dy: Float, color: Int) {
            c.save()
            c.translate(cx + dx * r, cy + dy * r)
            c.scale(r * scale, r * scale)
            fill.color = color
            c.drawPath(shape, fill)
            c.restore()
        }
        if (t.special != 0) specialCount++
        val pulse = (0.5f + 0.5f * sin(System.currentTimeMillis() / 220.0)).toFloat()
        if (t.special == RAINBOW) {
            c.save()
            c.translate(cx, cy)
            c.rotate(System.currentTimeMillis() % 4000L / 4000f * 360f)
            fill.shader = rainbowShader
            fill.alpha = a
            c.drawCircle(0f, 0f, r * 1.05f, fill)
            fill.shader = null
            c.restore()
            c.save()
            c.translate(cx, cy)
            c.scale(r * 0.62f, r * 0.62f)
            fill.color = withAlpha(Color.WHITE, a)
            c.drawPath(starPath, fill)
            c.restore()
            return
        }
        if (t.special == BOMB) {
            line.color = withAlpha(Color.WHITE, ((0.45f + 0.55f * pulse) * a).toInt())
            line.strokeWidth = r * 0.12f
            for (k in 0 until 8) {
                val ang = k * Math.PI / 4
                val r0 = r * (1.12f + 0.06f * pulse)
                val r1 = r * (1.32f + 0.14f * pulse)
                c.drawLine(cx + (cos(ang) * r0).toFloat(), cy + (sin(ang) * r0).toFloat(), cx + (cos(ang) * r1).toFloat(), cy + (sin(ang) * r1).toFloat(), line)
            }
        }
        poly(1f, 0f, 0.12f, Color.argb(a * 3 / 10, 0, 0, 0))
        poly(1f, 0f, 0f, withAlpha(darken(base, 0.72f), a))
        poly(0.84f, -0.03f, -0.04f, withAlpha(base, a))
        poly(0.46f, -0.14f, -0.2f, withAlpha(lighten(base, 0.5f), a / 2))

        text.color = withAlpha(Color.WHITE, a)
        text.textSize = r * 1.05f
        text.setShadowLayer(r * 0.08f, 0f, r * 0.05f, Color.argb(a * 6 / 10, 0, 0, 0))
        val yOff = r * 0.08f - (text.ascent() + text.descent()) / 2f
        c.drawText(t.num.toString(), cx, cy + yOff, text)
        text.clearShadowLayer()
        if (t.special == BOMB) { // fuse spark
            fill.color = withAlpha(Color.parseColor("#FFD23F"), a)
            c.drawCircle(cx + r * 0.62f, cy - r * 0.78f, r * (0.14f + 0.06f * pulse), fill)
        }
    }

    private fun drawParticles(c: Canvas) {
        if (particles.isEmpty()) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            p.vy += 900f * unit * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            fill.color = withAlpha(p.color, (p.life / 0.6f * 255).toInt().coerceIn(0, 255))
            c.drawCircle(p.x, p.y, (4f + 6f * p.life) * unit, fill)
        }
        if (particles.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun drawMessage(c: Canvas) {
        val msg = message ?: return
        val lines = msg.split("\n")
        text.color = withAlpha(Color.parseColor("#FFD23F"), (messageAlpha * 255).toInt().coerceIn(0, 255))
        text.setShadowLayer(8 * unit, 0f, 4 * unit, Color.argb((messageAlpha * 160).toInt().coerceIn(0, 255), 0, 0, 0))
        text.textSize = 64 * unit * messageScale
        val cy = boardTop + cell * N / 2f
        lines.forEachIndexed { i, l -> c.drawText(l, width / 2f, cy + i * 74 * unit * messageScale, text) }
        text.clearShadowLayer()
    }

    private fun drawGameOver(c: Canvas) {
        fill.color = Color.argb(185, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        text.color = Color.WHITE
        text.textSize = 76 * unit
        c.drawText("GAME OVER", width / 2f, height * 0.36f, text)
        text.textSize = 50 * unit
        c.drawText("Score $score", width / 2f, height * 0.36f + 90 * unit, text)
        text.color = Color.parseColor("#3DDC97")
        text.textSize = 38 * unit
        c.drawText(if (score >= best && score > 0) "New best!" else "Best $best", width / 2f, height * 0.36f + 150 * unit, text)

        val bw = 380 * unit
        val bh = 110 * unit
        restartRect.set((width - bw) / 2f, height * 0.36f + 210 * unit, (width + bw) / 2f, height * 0.36f + 210 * unit + bh)
        fill.color = Color.parseColor("#9B6DFF")
        c.drawRoundRect(restartRect, 28 * unit, 28 * unit, fill)
        text.color = Color.WHITE
        text.textSize = 44 * unit
        c.drawText("PLAY AGAIN", restartRect.centerX(), restartRect.centerY() + 15 * unit, text)
    }

    // ---- color / shape utils ----

    private fun withAlpha(color: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    private fun darken(color: Int, f: Float) = Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())
    private fun lighten(color: Int, f: Float) = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * f).toInt()
    )

    /** Unit-radius outline per color (a different shape per color helps colour-blind players). */
    private fun shapePath(kind: Int): Path {
        val p = Path()
        fun ngon(n: Int, r: Float, rot: Double) {
            for (i in 0 until n) {
                val a = rot + 2 * Math.PI * i / n
                val px = (cos(a) * r).toFloat()
                val py = (sin(a) * r).toFloat()
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close()
        }
        when (kind) {
            0 -> p.addCircle(0f, 0f, 1f, Path.Direction.CW)
            1 -> { // rounded square (superellipse)
                val n = 32
                for (i in 0 until n) {
                    val a = 2 * Math.PI * i / n
                    val cs = cos(a)
                    val sn = sin(a)
                    val px = (sign(cs) * abs(cs).pow(0.5) * 0.95).toFloat()
                    val py = (sign(sn) * abs(sn).pow(0.5) * 0.95).toFloat()
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                p.close()
            }
            2 -> ngon(4, 1.12f, -Math.PI / 2)  // diamond
            3 -> ngon(6, 1.02f, 0.0)           // hexagon
            else -> ngon(3, 1.18f, -Math.PI / 2) // triangle
        }
        return p
    }
}
