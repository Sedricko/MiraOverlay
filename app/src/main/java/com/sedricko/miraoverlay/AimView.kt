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
    private val shotLocks = mutableListOf<Float?>() // ângulo travado de cada tiro do plano
    private var extraLock: Float? = null
    private val MAX_EXTRA = 6 // limite real do jogo (informado pelo usuário): 6 ricochetes

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

    // A bala sai da PONTA da arma, não do corpo: ombro um pouco à frente do cabelo (lado da mira) + comprimento da arma.
    // shortcut: medidas em fração da largura da tela, tiradas de 2 prints; refinar se a linha ainda sair torta.
    // o braço sobe e desce com a mira: a base da arma fica ~0,04*largura abaixo do cabelo mirando para baixo e acima mirando para cima
    private fun pivotX(dirX: Float) = origin.x + (if (dirX >= 0f) 1f else -1f) * 0.036f * width
    private fun pivotY(dirY: Float) = origin.y + 0.042f * width * dirY
    private fun muzzle(dx: Float, dy: Float) = PointF(pivotX(dx) + dx * 0.048f * width, pivotY(dy) + dy * 0.048f * width)
    /** direção unitária do corpo até a mira que o dedo segura (a base da arma depende da própria direção: 2 passadas) */
    private fun dirTo(p: PointF): PointF {
        var dx = p.x - origin.x; var dy = p.y - origin.y; var l = hypot(dx, dy).coerceAtLeast(1f)
        repeat(2) {
            dx = p.x - pivotX(dx); dy = p.y - pivotY(dy / l); l = hypot(dx, dy).coerceAtLeast(1f)
        }
        return PointF(dx / l, dy / l)
    }

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
        val m0 = muzzle(dirX, dirY)
        val pts = mutableListOf(m0)
        var px = m0.x; var py = m0.y
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

    /** Tiro que atravessa os inimigos vivos: caminho até o último acerto, quantos acerta e quais. */
    private fun traceAll(dirX: Float, dirY: Float, area: RectF, block: RectF, nb: Int, alive: BooleanArray): Triple<List<PointF>, Int, BooleanArray> {
        val m0 = muzzle(dirX, dirY)
        val pts = mutableListOf(m0)
        val got = BooleanArray(targets.size)
        var px = m0.x; var py = m0.y
        var dx = dirX; var dy = dirY
        var cutSeg = -1; var cutProj = 0f; var cutX = 0f; var cutY = 0f; var cutDx = 0f; var cutDy = 0f
        for (i in 0..nb) {
            if (abs(dx) < 1e-6f) dx = 1e-6f
            if (abs(dy) < 1e-6f) dy = 1e-6f
            val (bestT, flipX) = next(px, py, dx, dy, area, block)
            val segLen = if (bestT == Float.MAX_VALUE) 4000f else bestT
            targets.forEachIndexed { k, t ->
                if (!alive[k]) return@forEachIndexed
                val proj = ((t.x - px) * dx + (t.y - py) * dy).coerceIn(0f, segLen)
                if (hypot(px + dx * proj - t.x, py + dy * proj - t.y) < hitRadius) {
                    got[k] = true
                    if (cutSeg != i) { cutSeg = i; cutProj = proj; cutX = px; cutY = py; cutDx = dx; cutDy = dy } else if (proj > cutProj) cutProj = proj
                }
            }
            px += dx * segLen; py += dy * segLen
            pts.add(PointF(px, py))
            if (bestT == Float.MAX_VALUE) break
            if (flipX) dx = -dx else dy = -dy
        }
        if (cutSeg < 0) return Triple(pts, 0, got)
        val cut = pts.subList(0, cutSeg + 1).toMutableList() // para no último inimigo atingido
        cut.add(PointF(cutX + cutDx * cutProj, cutY + cutDy * cutProj))
        return Triple(cut, got.count { it }, got)
    }

    private class Route(val ang: Float, val count: Int, val pts: List<PointF>, val hit: BooleanArray, val width: Int)

    /**
     * Todos os caminhos distintos que acertam o MAIOR número de inimigos vivos com até nb ricochetes.
     * Ordem: mais acertos; depois só rotas com folga de mira (>= 0,5°) e menos ricochetes; depois a mais larga.
     */
    private fun routesFor(nb: Int, area: RectF, block: RectF, alive: BooleanArray): List<Route> {
        val n = 3600; val cnt = IntArray(n)
        for (a in 0 until n) { val g = a * (2 * Math.PI / n).toFloat(); cnt[a] = traceAll(cos(g), sin(g), area, block, nb, alive).second }
        val best = cnt.max(); if (best == 0) return emptyList()
        val runs = mutableListOf<MutableList<Int>>()
        for (a in 0 until n) if (cnt[a] == best) { if (runs.isNotEmpty() && a - runs.last().last() <= 8) runs.last().add(a) else runs.add(mutableListOf(a)) }
        val routes = runs.map { r ->
            val a = r[r.size / 2]; val g = a * (2 * Math.PI / n).toFloat()
            val (p, c, h) = traceAll(cos(g), sin(g), area, block, nb, alive)
            Route(g, c, p, h, r.size)
        }
        return routes.sortedWith(compareBy({ if (it.width >= 5) 0 else 1 }, { if (it.width >= 5) it.pts.size else 0 }, { -it.width }))
    }

    private fun angDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % (2 * Math.PI.toFloat())
        return min(d, 2 * Math.PI.toFloat() - d)
    }

    private fun aimOf(ang: Float): PointF { val m = muzzle(cos(ang), sin(ang)); return PointF(m.x + cos(ang) * 300f, m.y + sin(ang) * 300f) }

    /**
     * Plano para matar TODOS os inimigos gastando o mínimo de tiros: cada tiro mata o máximo possível de inimigos
     * (a bala atravessa) e o próximo cuida dos que sobraram.
     * kind 0 = tiro 1; 1 = tiros seguintes; 2 = caminhos alternativos para o tiro 1; 3 = tiro 1 com mais ricochetes que o Ric.
     * A rota de cada tiro fica TRAVADA enquanto continuar acertando o mesmo tanto; recalcula a cada leitura da tela,
     * então acompanha o atirador quando ele se mexe.
     */
    private fun solve(): List<Triple<List<PointF>, PointF, Int>> {
        if (targets.isEmpty()) { shotLocks.clear(); extraLock = null; return emptyList() }
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        val out = mutableListOf<Triple<List<PointF>, PointF, Int>>()
        val alive = BooleanArray(targets.size) { true }
        var first: Route? = null
        for (shot in 0 until 5) {
            if (alive.none { it }) break
            val routes = routesFor(bounces, area, block, alive)
            if (routes.isEmpty()) break
            val lock = shotLocks.getOrNull(shot)
            val pick = routes.firstOrNull { lock != null && angDiff(it.ang, lock) < 0.03f } ?: routes.first()
            while (shotLocks.size <= shot) shotLocks.add(null)
            shotLocks[shot] = pick.ang
            out.add(Triple(pick.pts, aimOf(pick.ang), if (shot == 0) 0 else 1))
            if (shot == 0) { first = pick; routes.filter { it !== pick }.take(1).forEach { out.add(Triple(it.pts, aimOf(it.ang), 2)) } }
            pick.hit.forEachIndexed { k, h -> if (h) alive[k] = false }
        }
        while (shotLocks.size > out.count { it.third <= 1 }) shotLocks.removeAt(shotLocks.size - 1)
        // com mais ricochetes dá para matar mais gente no primeiro tiro? sugere em roxo
        val f = first
        val more = if (f != null && bounces < MAX_EXTRA) routesFor(MAX_EXTRA, area, block, BooleanArray(targets.size) { true }) else emptyList()
        val m = more.firstOrNull { extraLock != null && angDiff(it.ang, extraLock!!) < 0.03f } ?: more.firstOrNull()
        if (f != null && m != null && m.count > f.count) { extraLock = m.ang; out.add(Triple(m.pts, aimOf(m.ang), 3)) } else extraLock = null
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
            val d = dirTo(p)
            drawPath(c, trace(d.x, d.y, area, block, null).first, byBounce = false)
            c.drawCircle(p.x, p.y, 40f, line)
            c.drawLine(p.x - 60f, p.y, p.x + 60f, p.y, line)
            c.drawLine(p.x, p.y - 60f, p.x, p.y + 60f, line)
            return
        }
        // destino final da bala para a mira que você está segurando
        crosshair?.let { p ->
            val d = dirTo(p); val len = hypot(p.x - origin.x, p.y - origin.y)
            if (len > 5f) {
                val dx = d.x; val dy = d.y
                val pts = trace(dx, dy, area, block, null).first
                line.color = 0xFF2F6BFF.toInt(); line.strokeWidth = 5f
                drawPath(c, pts, byBounce = false)
                val e = pts.last(); val hit = targets.any { trace(dx, dy, area, block, it).second }
                line.color = if (hit) 0xFF34C759.toInt() else 0xFFFF3B30.toInt()
                c.drawCircle(e.x, e.y, 30f, line); dot.color = line.color; c.drawCircle(e.x, e.y, 10f, dot)
                line.strokeWidth = 6f
            }
        }
        // guia: rotas que acertam; o ponto azul é onde apontar no jogo
        // segurando a mira: só a melhor rota, para a tela não encher de linhas
        solve().filter { crosshair == null || it.third == 0 || it.third == 3 }.sortedByDescending { it.third }.forEach { (pts, aim, kind) ->
            line.strokeWidth = if (kind == 0 || kind == 3) 7f else 4f
            line.pathEffect = if (kind >= 2) DashPathEffect(floatArrayOf(26f, 14f), 0f) else null // tracejada = alternativa / mais ricochetes
            drawPath(c, pts)
            line.pathEffect = null
            dot.color = 0xFF0A84FF.toInt(); c.drawCircle(aim.x, aim.y, 14f, dot)
        }
        line.strokeWidth = 6f
    }

    // cor de cada trecho = quantas batidas já deu: 1º reto verde, 2º amarelo, 3º laranja, 4º rosa, 5º+ branco
    private val bounceColors = intArrayOf(0xFF34C759.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9500.toInt(), 0xFFFF2D92.toInt(), 0xFFFFFFFF.toInt())
    private fun drawPath(c: Canvas, pts: List<PointF>, byBounce: Boolean = true) {
        val keep = line.color
        for (i in 0 until pts.size - 1) {
            if (byBounce) line.color = bounceColors[min(i, bounceColors.size - 1)]
            c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, line)
        }
        line.color = keep
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
