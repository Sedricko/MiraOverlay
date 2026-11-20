package com.sedricko.miraoverlay

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

class Detection(val shooter: PointF?, val enemies: List<PointF>)

private class Blob(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
}

/**
 * Acha na área de jogo as manchas escuras em pé (roupas/cabelo).
 * A atiradora é a que tem cabelo loiro; as demais são inimigos.
 * shortcut: heurística de cor; fases escuras ou inimigo de cabelo loiro podem falhar, trocar por template matching.
 */
fun findTargets(b: Bitmap, a: RectF, oldShooter: PointF): Detection {
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
    val blobs = mutableListOf<Blob>()
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
        val bl = Blob(a.left + minC * cell, a.top + minR * cell, a.left + (maxC + 1) * cell, a.top + (maxR + 1) * cell)
        if (bl.h >= 80 && bl.h >= 1.1f * bl.w) blobs.add(bl) // descarta corpo caído e manchas pequenas
    }

    // cabelo loiro = só a atiradora (a linha do bloco no overlay é rosa para não confundir)
    fun blonde(bl: Blob): Int {
        var n = 0
        var yy = bl.t.toInt()
        while (yy < bl.b && yy < b.height) {
            var xx = bl.l.toInt()
            while (xx < bl.r && xx < b.width) {
                val p = b.getPixel(xx, yy)
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
                if (r > 190 && g in 140..215 && bb < 120) n++
                xx += 3
            }
            yy += 3
        }
        return n
    }
    var shooterBlob = blobs.maxByOrNull { blonde(it) }?.takeIf { blonde(it) >= 25 }
    if (shooterBlob == null) // sem loiro: o mais perto da posição anterior da atiradora
        shooterBlob = blobs.firstOrNull { abs(oldShooter.x - it.cx) < it.w / 2 + 90 && oldShooter.y > it.t - 90 && oldShooter.y < it.b + 90 }
    val enemies = blobs.filter { it !== shooterBlob }.map { PointF(it.cx, it.t + it.h * 0.4f) }
    val shooter = shooterBlob?.let { PointF(it.cx, (it.b - 134f).coerceAtLeast(a.top)) } // altura do cano acima do chão
    return Detection(shooter, enemies)
}
