package com.sedricko.miraoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Camada de desenho em tela cheia. Nunca captura toque: o jogo abaixo continua jogável. */
class AimView(ctx: Context) : View(ctx) {
    var bounces = 3
        set(v) { field = v.coerceIn(0, 8); invalidate() }

    val origin = PointF()   // atirador
    val target = PointF()   // inimigo
    // paredes: área de jogo e bloco central (dois cantos opostos cada)
    val areaA = PointF(); val areaB = PointF()
    val blockA = PointF(); val blockB = PointF()

    private val hitRadius = 45f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rect(a: PointF, b: PointF) = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

    /** Dispara na direção (dx,dy) e devolve o caminho; hit=true se o tiro passou pelo alvo. */
    private fun trace(dirX: Float, dirY: Float, area: RectF, block: RectF): Pair<List<PointF>, Boolean> {
        val pts = mutableListOf(PointF(origin.x, origin.y))
        var px = origin.x; var py = origin.y
        var dx = dirX; var dy = dirY
        for (i in 0..bounces) {
            if (abs(dx) < 1e-6f) dx = 1e-6f
            if (abs(dy) < 1e-6f) dy = 1e-6f
            var bestT = Float.MAX_VALUE; var flipX = false

            // parede da área: reflete ao sair
            val tx = if (dx > 0) (area.right - px) / dx else (area.left - px) / dx
            val ty = if (dy > 0) (area.bottom - py) / dy else (area.top - py) / dy
            if (tx > 1e-3f && tx < bestT) { bestT = tx; flipX = true }
            if (ty > 1e-3f && ty < bestT) { bestT = ty; flipX = false }

            // bloco: reflete na face atingida
            if (!block.isEmpty) {
                val bx1 = (block.left - px) / dx; val bx2 = (block.right - px) / dx
                val by1 = (block.top - py) / dy; val by2 = (block.bottom - py) / dy
                val enterX = min(bx1, bx2); val enterY = min(by1, by2)
                val enter = max(enterX, enterY); val exit = min(max(bx1, bx2), max(by1, by2))
                if (enter > 1e-3f && enter < exit && enter < bestT) { bestT = enter; flipX = enterX > enterY }
            }

            val segLen = if (bestT == Float.MAX_VALUE) 4000f else bestT
            // o tiro passa perto do inimigo neste trecho?
            val proj = ((target.x - px) * dx + (target.y - py) * dy).coerceIn(0f, segLen)
            val cx = px + dx * proj; val cy = py + dy * proj
            if (hypot(cx - target.x, cy - target.y) < hitRadius) { pts.add(PointF(cx, cy)); return pts to true }

            px += dx * segLen; py += dy * segLen
            pts.add(PointF(px, py))
            if (bestT == Float.MAX_VALUE) break
            if (flipX) dx = -dx else dy = -dy
        }
        return pts to false
    }

    /** Varre os ângulos e escolhe o que acerta o alvo com menos ricochetes. */
    private fun solve(area: RectF, block: RectF): Pair<List<PointF>, Float>? {
        var best: List<PointF>? = null; var bestAng = 0f
        for (a in 0 until 3600) {
            val ang = a * (2 * Math.PI / 3600).toFloat()
            val (pts, hit) = trace(cos(ang), sin(ang), area, block)
            if (hit && (best == null || pts.size < best.size)) { best = pts; bestAng = ang }
        }
        return best?.let { it to bestAng }
    }

    override fun onDraw(c: Canvas) {
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        dash.color = 0xFF34C759.toInt(); c.drawRect(area, dash)
        dash.color = 0xFFFFCC00.toInt(); c.drawRect(block, dash)

        // mira (crosshair) no inimigo
        c.drawCircle(target.x, target.y, 40f, line)
        c.drawLine(target.x - 60f, target.y, target.x + 60f, target.y, line)
        c.drawLine(target.x, target.y - 60f, target.x, target.y + 60f, line)

        val sol = solve(area, block)
        if (sol == null) { // sem caminho com esse número de ricochetes
            line.color = 0x88FFFFFF.toInt()
            c.drawLine(origin.x, origin.y, target.x, target.y, line)
            line.color = 0xFFFF3B30.toInt()
            return
        }
        val (pts, ang) = sol
        for (i in 0 until pts.size - 1) c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, line)
        dot.color = 0xFFFFFFFF.toInt()
        for (i in 1 until pts.size - 1) c.drawCircle(pts[i].x, pts[i].y, 10f, dot)
        // ponto na direção certa do tiro, perto do atirador: é para onde apontar no jogo
        dot.color = 0xFF0A84FF.toInt()
        c.drawCircle(origin.x + cos(ang) * 250f, origin.y + sin(ang) * 250f, 14f, dot)
    }
}
