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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Janela do tamanho da área de jogo. Segure e arraste: a linha com ricochetes acompanha o dedo.
 * Ao soltar, onShoot recebe o ponto (coordenadas de tela) para tocar no jogo.
 * Fora desta janela (botões, itens) os toques vão direto para o jogo.
 */
class AimView(ctx: Context) : View(ctx) {
    var bounces = 3
        set(v) { field = v.coerceIn(0, 8); invalidate() }
    var onShoot: ((Float, Float) -> Unit)? = null
    var offX = 0f   // canto da janela na tela
    var offY = 0f

    val origin = PointF()   // atirador
    // paredes: área de jogo e bloco central (dois cantos opostos cada)
    val areaA = PointF(); val areaB = PointF()
    val blockA = PointF(); val blockB = PointF()
    private var aimPt: PointF? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rect(a: PointF, b: PointF) = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

    private fun trace(dirX: Float, dirY: Float, area: RectF, block: RectF): List<PointF> {
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

            if (bestT == Float.MAX_VALUE) { pts.add(PointF(px + dx * 4000, py + dy * 4000)); return pts }
            px += dx * bestT; py += dy * bestT
            pts.add(PointF(px, py))
            if (flipX) dx = -dx else dy = -dy
        }
        return pts
    }

    override fun onDraw(c: Canvas) {
        c.translate(-offX, -offY) // desenha em coordenadas de tela
        val area = rect(areaA, areaB); val block = rect(blockA, blockB)
        dash.color = 0xFF34C759.toInt(); c.drawRect(area, dash)
        dash.color = 0xFFFFCC00.toInt(); c.drawRect(block, dash)

        val p = aimPt ?: return
        val dx = p.x - origin.x; val dy = p.y - origin.y
        val len = hypot(dx, dy)
        if (len < 1f) return
        val pts = trace(dx / len, dy / len, area, block)
        for (i in 0 until pts.size - 1) c.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, line)
        dot.color = 0xFFFFFFFF.toInt()
        for (i in 1 until pts.size - 1) c.drawCircle(pts[i].x, pts[i].y, 10f, dot)

        // mira no ponto do dedo
        c.drawCircle(p.x, p.y, 40f, line)
        c.drawLine(p.x - 60f, p.y, p.x + 60f, p.y, line)
        c.drawLine(p.x, p.y - 60f, p.x, p.y + 60f, line)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                aimPt = PointF(e.rawX, e.rawY); invalidate()
            }
            MotionEvent.ACTION_UP -> onShoot?.invoke(e.rawX, e.rawY)
        }
        return true
    }
}
