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
    var targets: List<PointF> = emptyList()
    var onShoot: ((Float, Float) -> Unit)? = null
    var offX = 0f   // canto da janela na tela
    var offY = 0f

    val origin = PointF()   // atirador
    val areaA = PointF(); val areaB = PointF()
    val blockA = PointF(); val blockB = PointF()
    private var aimPt: PointF? = null

    private val hitRadius = 45f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rect(a: PointF, b: PointF) = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

    /** Dispara na direção dada; hit=true se passou por t (quando t != null). */
    private fun trace(dirX: Float, dirY: Float, area: RectF, block: RectF, t: PointF?): Triple<List<PointF>, Boolean, Float> {
        val pts = mutableListOf(PointF(origin.x, origin.y))
        var px = origin.x; var py = origin.y
        var dx = dirX; var dy = dirY
        for (i in 0..bounces) {
            if (abs(dx) < 1e-6f) dx = 1e-6f
            if (abs(dy) < 1e-6f) dy = 1e-6f
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

    /** Varre 360° e devolve até 3 rotas que acertam o inimigo (menos ricochetes primeiro), cada uma com o ponto de mira. */
    private fun solve(): List<Pair<List<PointF>, PointF>> {
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        val cands = mutableListOf<Pair<List<PointF>, Float>>()
        for (t in targets) {
            val cluster = mutableListOf<Triple<List<PointF>, Float, Float>>()
            var last = -1000
            // escolhe o ângulo que passa mais perto do centro do inimigo
            fun flush() { cluster.minByOrNull { it.third }?.let { cands.add(it.first to it.second) }; cluster.clear() }
            for (a in 0 until 3600) {
                val ang = a * (2 * Math.PI / 3600).toFloat()
                val (pts, hit, d) = trace(cos(ang), sin(ang), area, block, t)
                if (!hit) continue
                if (a - last > 30) flush() // mais de 3° de diferença = rota diferente
                cluster.add(Triple(pts, ang, d)); last = a
            }
            flush()
        }
        return cands.sortedBy { it.first.size }.take(3)
            .map { (pts, ang) -> pts to PointF(origin.x + cos(ang) * 300f, origin.y + sin(ang) * 300f) }
    }

    override fun onDraw(c: Canvas) {
        c.translate(-offX, -offY) // desenha em coordenadas de tela
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        dash.color = 0xFF34C759.toInt(); c.drawRect(area, dash)
        dash.color = 0xFFFFCC00.toInt(); c.drawRect(block, dash)

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
        // guia: rotas que acertam; o ponto azul é onde apontar no jogo
        solve().forEachIndexed { i, (pts, aim) ->
            line.color = if (i == 0) 0xFF34C759.toInt() else 0xFF00C7FF.toInt()
            line.strokeWidth = if (i == 0) 6f else 4f
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
