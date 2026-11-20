package com.sedricko.miraoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Janela do tamanho da área de jogo.
 * Guia (autoMode): mostra as rotas com ricochete que acertam o inimigo e deixa os toques passarem para o jogo.
 * Manual: a linha acompanha o dedo e, ao soltar, o app toca no jogo nesse ponto.
 * Fora desta janela (botões, itens) os toques vão direto para o jogo.
 */
class AimView(ctx: Context) : View(ctx) {
    var bounces = 3
        set(v) { field = v.coerceIn(0, 8); invalidate() }
    var autoMode = true
    var crosshair: PointF? = null // mira do jogo (detectada no print): mostramos para onde a bala vai
    var targets: List<PointF> = emptyList()
        private set
    private var locks = listOf<Pair<PointF, Float>>() // (inimigo, ângulo da rota travada)
    private var multiLock: Float? = null

    /** Atualiza os alvos; se um inimigo mal se mexeu (<14px) mantém o ponto antigo, para a rota não tremer. */
    fun updateTargets(new: List<PointF>) {
        val old = targets
        targets = new.map { n -> old.firstOrNull { hypot(it.x - n.x, it.y - n.y) < 14f } ?: n }
        invalidate()
    }
    var onShoot: ((Float, Float) -> Unit)? = null
    var offX = 0f   // canto da janela na tela
    var offY = 0f

    val origin = PointF()   // atirador
    val areaA = PointF(); val areaB = PointF()
    val blockA = PointF(); val blockB = PointF()
    private var aimPt: PointF? = null
    private var walls: BooleanArray? = null // paredes lidas do print; sem elas usa a área + bloco manuais
    private var gCols = 0; private var gRows = 0; private var gx0 = 0f; private var gy0 = 0f
    private val cell = 16f

    fun setWalls(d: Detection) { walls = d.walls; gCols = d.cols; gRows = d.rows; gx0 = d.left; gy0 = d.top }

    /** Primeira parede na grade (DDA): distância e se a parede é vertical (inverte x). */
    private fun nextGrid(g: BooleanArray, px: Float, py: Float, dx: Float, dy: Float): Pair<Float, Boolean> {
        val lx = px + dx * 0.05f - gx0; val ly = py + dy * 0.05f - gy0 // sai um pouco da parede para não colar nela
        var cx = kotlin.math.floor(lx / cell).toInt(); var cy = kotlin.math.floor(ly / cell).toInt()
        val sx = if (dx > 0) 1 else -1; val sy = if (dy > 0) 1 else -1
        val tdx = abs(cell / dx); val tdy = abs(cell / dy)
        var tmx = ((if (dx > 0) (cx + 1) * cell else cx * cell) - lx) / dx
        var tmy = ((if (dy > 0) (cy + 1) * cell else cy * cell) - ly) / dy
        for (i in 0 until 2000) {
            val t: Float; val axisX: Boolean
            if (tmx < tmy) { t = tmx; cx += sx; tmx += tdx; axisX = true } else { t = tmy; cy += sy; tmy += tdy; axisX = false }
            if (cx < 0 || cy < 0 || cx >= gCols || cy >= gRows) return Float.MAX_VALUE to false // borda da tela não é parede: a bala sai
            if (g[cy * gCols + cx]) return (t + 0.05f) to axisX
        }
        return Float.MAX_VALUE to false
    }

    private val hitRadius = 45f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rect(a: PointF, b: PointF) = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

    /** Primeira parede/bloco no caminho: distância e se a parede é vertical (inverte x). */
    private fun next(px: Float, py: Float, dx: Float, dy: Float, area: RectF, block: RectF): Pair<Float, Boolean> {
        walls?.let { return nextGrid(it, px, py, dx, dy) }
        var bestT = Float.MAX_VALUE; var flipX = false
        val tx = if (dx > 0) (area.right - px) / dx else (area.left - px) / dx
        val ty = if (dy > 0) (area.bottom - py) / dy else (area.top - py) / dy
        if (tx > 1e-3f && tx < bestT) { bestT = tx; flipX = true }
        if (ty > 1e-3f && ty < bestT) { bestT = ty; flipX = false }
        if (!block.isEmpty) {
            val bx1 = (block.left - px) / dx; val bx2 = (block.right - px) / dx
            val by1 = (block.top - py) / dy; val by2 = (block.bottom - py) / dy
            val enterX = min(bx1, bx2); val enterY = min(by1, by2)
            val enter = max(enterX, enterY); val exit = min(max(bx1, bx2), max(by1, by2))
            if (enter > 1e-3f && enter < exit && enter < bestT) { bestT = enter; flipX = enterX > enterY }
        }
        return bestT to flipX
    }

    /** Dispara na direção dada; hit=true (e d=distância) se o tiro passa por t. */
    private fun trace(dirX: Float, dirY: Float, area: RectF, block: RectF, t: PointF?): Triple<List<PointF>, Boolean, Float> {
        val pts = mutableListOf(PointF(origin.x, origin.y))
        var px = origin.x; var py = origin.y
        var dx = dirX; var dy = dirY
        for (i in 0..bounces) {
            if (abs(dx) < 1e-6f) dx = 1e-6f
            if (abs(dy) < 1e-6f) dy = 1e-6f
            val (bestT, flipX) = next(px, py, dx, dy, area, block)
            val segLen = if (bestT == Float.MAX_VALUE) 4000f else bestT
            if (t != null) {
                val proj = ((t.x - px) * dx + (t.y - py) * dy).coerceIn(0f, segLen)
                val cx = px + dx * proj; val cy = py + dy * proj
                val d = hypot(cx - t.x, cy - t.y)
                if (d < hitRadius) { pts.add(PointF(cx, cy)); return Triple(pts, true, d) }
            }
            px += dx * segLen; py += dy * segLen
            pts.add(PointF(px, py))
            if (bestT == Float.MAX_VALUE) break
            if (flipX) dx = -dx else dy = -dy
        }
        return Triple(pts, false, 0f)
    }

    /** Tiro que atravessa: caminho completo e quantos inimigos ele atinge. */
    private fun traceAll(dirX: Float, dirY: Float, area: RectF, block: RectF): Pair<List<PointF>, Int> {
        val pts = mutableListOf(PointF(origin.x, origin.y))
        val got = BooleanArray(targets.size)
        var px = origin.x; var py = origin.y
        var dx = dirX; var dy = dirY
        for (i in 0..bounces) {
            if (abs(dx) < 1e-6f) dx = 1e-6f
            if (abs(dy) < 1e-6f) dy = 1e-6f
            val (bestT, flipX) = next(px, py, dx, dy, area, block)
            val segLen = if (bestT == Float.MAX_VALUE) 4000f else bestT
            targets.forEachIndexed { k, t ->
                val proj = ((t.x - px) * dx + (t.y - py) * dy).coerceIn(0f, segLen)
                if (hypot(px + dx * proj - t.x, py + dy * proj - t.y) < hitRadius) got[k] = true
            }
            px += dx * segLen; py += dy * segLen
            pts.add(PointF(px, py))
            if (bestT == Float.MAX_VALUE) break
            if (flipX) dx = -dx else dy = -dy
        }
        return pts to got.count { it }
    }

    private fun aimOf(ang: Float) = PointF(origin.x + cos(ang) * 300f, origin.y + sin(ang) * 300f)

    private fun angDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % (2 * Math.PI.toFloat())
        return min(d, 2 * Math.PI.toFloat() - d)
    }

    /**
     * Rotas: a melhor para cada inimigo (cor 0 = menos ricochetes, 1 = as outras)
     * e, se existir, uma que acerta todos de uma vez (cor 2, vale se a bala atravessar).
     * A rota fica TRAVADA enquanto continuar acertando; só troca quando deixa de acertar.
     */
    private fun solve(): List<Triple<List<PointF>, PointF, Int>> {
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        val per = mutableListOf<Pair<List<PointF>, PointF>>()
        val newLocks = mutableListOf<Pair<PointF, Float>>()
        for (t in targets) {
            val oldAng = locks.firstOrNull { hypot(it.first.x - t.x, it.first.y - t.y) < 40f }?.second
            var route: Pair<List<PointF>, Float>? = null
            if (oldAng != null) { // a rota travada ainda acerta?
                val (pts, hit, _) = trace(cos(oldAng), sin(oldAng), area, block, t)
                if (hit) route = pts to oldAng
            }
            if (route == null) {
                val reps = mutableListOf<Triple<List<PointF>, Float, Float>>() // pts, ang, distância ao centro
                val cluster = mutableListOf<Triple<List<PointF>, Float, Float>>()
                var last = -1000
                // dentro de um grupo de ângulos que acertam, o que passa mais perto do centro do inimigo
                fun flush() { cluster.minByOrNull { it.third }?.let { reps.add(it) }; cluster.clear() }
                for (a in 0 until 3600) {
                    val ang = a * (2 * Math.PI / 3600).toFloat()
                    val (pts, hit, d) = trace(cos(ang), sin(ang), area, block, t)
                    if (!hit) continue
                    if (a - last > 30) flush() // mais de 3° de diferença = rota diferente
                    cluster.add(Triple(pts, ang, d)); last = a
                }
                flush()
                // menos ricochetes; empate: a mais parecida com a anterior (ou a mais central)
                reps.minWithOrNull(compareBy({ it.first.size }, { if (oldAng != null) angDiff(it.second, oldAng) else it.third }))
                    ?.let { route = it.first to it.second }
            }
            route?.let { per.add(it.first to aimOf(it.second)); newLocks.add(t to it.second) }
        }
        locks = newLocks
        val out = per.sortedBy { it.first.size }.mapIndexed { i, r -> Triple(r.first, r.second, if (i == 0) 0 else 1) }.toMutableList()
        if (targets.size >= 2) {
            var ang: Float? = multiLock?.takeIf { traceAll(cos(it), sin(it), area, block).second == targets.size }
            if (ang == null) {
                var run = mutableListOf<Float>()
                for (a in 0..3600) {
                    val g = a * (2 * Math.PI / 3600).toFloat()
                    if (a < 3600 && traceAll(cos(g), sin(g), area, block).second == targets.size) { run.add(g); continue }
                    if (run.isNotEmpty() && ang == null) ang = run[run.size / 2]
                    run = mutableListOf()
                }
            }
            multiLock = ang
            ang?.let { out.add(Triple(traceAll(cos(it), sin(it), area, block).first, aimOf(it), 2)) }
        } else multiLock = null
        return out
    }

    override fun onDraw(c: Canvas) {
        c.translate(-offX, -offY) // desenha em coordenadas de tela
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        dash.color = 0xFF34C759.toInt(); c.drawRect(area, dash)
        if (walls == null) { dash.color = 0xFFFF6BD6.toInt(); c.drawRect(block, dash) }

        line.color = 0xFF34C759.toInt()
        for (t in targets) c.drawCircle(t.x, t.y, 50f, line) // inimigos reconhecidos

        if (!autoMode) { // manual: a linha acompanha o dedo
            line.color = 0xFFFF3B30.toInt()
            val p = aimPt ?: return
            val dx = p.x - origin.x; val dy = p.y - origin.y
            val len = hypot(dx, dy)
            if (len < 1f) return
            drawPath(c, trace(dx / len, dy / len, area, block, null).first)
            c.drawCircle(p.x, p.y, 40f, line)
            c.drawLine(p.x - 60f, p.y, p.x + 60f, p.y, line)
            c.drawLine(p.x, p.y - 60f, p.x, p.y + 60f, line)
            return
        }
        // destino final da bala para a mira que você está segurando
        crosshair?.let { p ->
            val dx = p.x - origin.x; val dy = p.y - origin.y; val len = hypot(dx, dy)
            if (len > 5f) {
                val pts = trace(dx / len, dy / len, area, block, null).first
                line.color = 0xFF2F6BFF.toInt(); line.strokeWidth = 5f
                drawPath(c, pts)
                val e = pts.last(); val hit = targets.any { trace(dx / len, dy / len, area, block, it).second }
                line.color = if (hit) 0xFF34C759.toInt() else 0xFFFF3B30.toInt()
                c.drawCircle(e.x, e.y, 30f, line); dot.color = line.color; c.drawCircle(e.x, e.y, 10f, dot)
                line.strokeWidth = 6f
            }
        }
        // guia: rotas que acertam; o ponto azul é onde apontar no jogo
        // segurando a mira: só a melhor rota, para a tela não encher de linhas
        solve().filter { crosshair == null || it.third == 0 }.sortedByDescending { it.third }.forEach { (pts, aim, kind) ->
            line.color = when (kind) { 0 -> 0xFF34C759.toInt(); 1 -> 0xFF00C7FF.toInt(); else -> 0xFFFF7000.toInt() }
            line.strokeWidth = if (kind == 1) 4f else 6f
            drawPath(c, pts)
            dot.color = 0xFF0A84FF.toInt(); c.drawCircle(aim.x, aim.y, 14f, dot)
        }
        line.strokeWidth = 6f
    }

    private fun drawPath(c: Canvas, pts: List<PointF>) {
        for (i in 0 until pts.size - 1) c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, line)
        dot.color = 0xFFFFFFFF.toInt()
        for (i in 1 until pts.size - 1) c.drawCircle(pts[i].x, pts[i].y, 10f, dot)
    }

    // só chega toque aqui no modo manual (no guia a janela deixa o toque passar para o jogo)
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { aimPt = PointF(e.rawX, e.rawY); invalidate() }
            MotionEvent.ACTION_UP -> onShoot?.invoke(e.rawX, e.rawY)
        }
        return true
    }
}
