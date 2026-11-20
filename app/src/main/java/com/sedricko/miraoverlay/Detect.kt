package com.sedricko.miraoverlay

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * Acha inimigos na área de jogo: manchas escuras e em pé (roupas/cabelo), exceto a do atirador.
 * shortcut: heurística de cor; fases com fundo escuro ou inimigo claro podem falhar, trocar por template matching.
 */
fun findTargets(b: Bitmap, a: RectF, shooter: PointF): List<PointF> {
    val cell = 16
    val cols = (a.width() / cell).toInt() + 1; val rows = (a.height() / cell).toInt() + 1
    val cnt = IntArray(cols * rows)
    var y = a.top.toInt() + 6
    while (y < a.bottom - 6 && y < b.height) {
        var x = a.left.toInt() + 6
        while (x < a.right - 6 && x < b.width) {
            val px = b.getPixel(x, y)
            if (max(max((px shr 16) and 255, (px shr 8) and 255), px and 255) < 75)
                cnt[((y - a.top) / cell).toInt() * cols + ((x - a.left) / cell).toInt()]++
            x += 4
        }
        y += 4
    }
    val seen = BooleanArray(cols * rows)
    val out = mutableListOf<PointF>()
    for (start in cnt.indices) {
        if (cnt[start] < 3 || seen[start]) continue
        var minC = cols; var maxC = 0; var minR = rows; var maxR = 0
        val stack = ArrayDeque<Int>().apply { add(start) }; seen[start] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); val c = i % cols; val r = i / cols
            minC = minOf(minC, c); maxC = max(maxC, c); minR = minOf(minR, r); maxR = max(maxR, r)
            for (dr in -2..2) for (dc in -2..2) {
                val nc = c + dc; val nr = r + dr
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val n = nr * cols + nc
                if (!seen[n] && cnt[n] >= 3) { seen[n] = true; stack.add(n) }
            }
        }
        val l = a.left + minC * cell; val r = a.left + (maxC + 1) * cell
        val t = a.top + minR * cell; val bt = a.top + (maxR + 1) * cell
        val w = r - l; val h = bt - t
        if (h < 80 || h < 1.1f * w) continue // descarta corpo caído e manchas pequenas
        if (abs(shooter.x - (l + r) / 2) < w / 2 + 90 && shooter.y > t - 90 && shooter.y < bt + 90) continue // é o atirador
        out.add(PointF((l + r) / 2, t + h * 0.4f))
    }
    return out
}
