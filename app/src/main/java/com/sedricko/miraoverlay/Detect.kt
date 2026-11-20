package com.sedricko.miraoverlay

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** walls: grade de células de 16px a partir de (left,top); true = parede de tijolo. */
class Detection(
    val shooter: PointF?, val enemies: List<PointF>,
    val walls: BooleanArray, val cols: Int, val rows: Int, val left: Float, val top: Float,
    val crosshair: PointF?, // mira dourada que o jogo desenha onde está o seu dedo
)

private class Cl(val l: Float, val t: Float, val r: Float, val b: Float, val tot: Int, val cx: Float, val cy: Float) {
    val w get() = r - l
    val h get() = b - t
}

private const val CELL = 16

/**
 * Lê o print da área de jogo:
 * - inimigos: manchas escuras em pé (roupas/cabelo); objetos explosivos vermelhos também são alvos
 * - atiradora: cabelo loiro ao lado de uma mancha escura (chapéu/botas)
 * - paredes: cor de tijolo (caixas e barris não contam)
 * Devolve null em tela que não é o jogo (vitória, transição escura).
 * shortcut: heurística de cor; fases de fundo escuro ou inimigo loiro podem falhar, trocar por template matching.
 */
fun findTargets(b: Bitmap, a: RectF): Detection? {
    val cols = (a.width() / CELL).toInt() + 1; val rows = (a.height() / CELL).toInt() + 1
    val dark = IntArray(cols * rows); val gold = IntArray(cols * rows); val red = IntArray(cols * rows)
    var y = a.top.toInt() + 6
    while (y < a.bottom - 6 && y < b.height) {
        var x = a.left.toInt() + 6
        while (x < a.right - 6 && x < b.width) {
            val p = b.getPixel(x, y)
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
            val i = ((y - a.top) / CELL).toInt() * cols + ((x - a.left) / CELL).toInt()
            if (max(max(r, g), bb) < 75) dark[i]++
            if (r > 190 && g in 140..215 && bb < 120) gold[i]++
            if (r > 170 && g < 80 && bb < 80) red[i]++
            x += 4
        }
        y += 4
    }

    // manchas = componentes conexos de células com pelo menos th amostras
    fun comps(cnt: IntArray, th: Int): List<Cl> {
        val seen = BooleanArray(cnt.size); val out = mutableListOf<Cl>()
        for (start in cnt.indices) {
            if (cnt[start] < th || seen[start]) continue
            var minC = cols; var maxC = 0; var minR = rows; var maxR = 0; var tot = 0; var sx = 0f; var sy = 0f
            val stack = ArrayDeque<Int>().apply { add(start) }; seen[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast(); val c = i % cols; val r = i / cols
                tot += cnt[i]; sx += cnt[i] * c; sy += cnt[i] * r
                minC = minOf(minC, c); maxC = max(maxC, c); minR = minOf(minR, r); maxR = max(maxR, r)
                for (dr in -2..2) for (dc in -2..2) {
                    val nc = c + dc; val nr = r + dr
                    if (nc !in 0 until cols || nr !in 0 until rows) continue
                    val n = nr * cols + nc
                    if (!seen[n] && cnt[n] >= th) { seen[n] = true; stack.add(n) }
                }
            }
            out.add(Cl(a.left + minC * CELL, a.top + minR * CELL, a.left + (maxC + 1) * CELL, a.top + (maxR + 1) * CELL,
                tot, a.left + (sx / tot + .5f) * CELL, a.top + (sy / tot + .5f) * CELL))
        }
        return out
    }

    val darks = comps(dark, 3)
    if (darks.any { it.w > 500 || it.h > 600 }) return null // tela escurecida: vitória/transição

    // roupa azul logo abaixo do cabelo: distingue a atiradora de brilhos/mira dourados
    fun blueBelow(cx: Float, cy: Float): Int {
        var n = 0; var yy = (cy + 15).toInt()
        while (yy < cy + 120 && yy < b.height) {
            var xx = (cx - 50).toInt().coerceAtLeast(0)
            while (xx < cx + 50 && xx < b.width) {
                val p = b.getPixel(xx, yy); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
                if (r < 110 && g < 140 && bb in 70..170 && bb > r + 30) n++
                xx += 4
            }
            yy += 4
        }
        return n
    }
    // atiradora = cabelo loiro perto de uma mancha escura e com roupa azul embaixo
    val golds = comps(gold, 1)
    val hair = golds.filter { h ->
        h.tot >= 6 && darks.any { abs(h.cx - (it.l + it.r) / 2) < it.w / 2 + 100 && h.cy > it.t - 100 && h.cy < it.b + 100 } &&
            blueBelow(h.cx, h.cy) >= 14
    }.maxByOrNull { it.tot }
    // y: altura do cano em relação ao cabelo
    val shooter = hair?.let { PointF(it.cx, it.cy + 8f) } // sem cabelo na tela: não move a atiradora
    // mira do jogo: outro dourado grande, longe da atiradora (o brilho do tiro também é dourado, mas dura pouco)
    val cross = golds.filter { it !== hair && it.tot >= 20 && it.w in 48f..160f && it.h in 48f..160f &&
        (shooter == null || hypot(it.cx - shooter.x, it.cy - shooter.y) > 100f) }.maxByOrNull { it.tot }?.let { PointF(it.cx, it.cy) }

    val enemies = darks.filter { d ->
        d.h >= 80 && d.h >= 1.1f * d.w && // descarta corpo caído e manchas pequenas
            !(shooter != null && abs(shooter.x - (d.l + d.r) / 2) < d.w / 2 + 100 && shooter.y > d.t - 100 && shooter.y < d.b + 100)
    }.map { PointF((it.l + it.r) / 2, it.t + it.h * 0.4f) }.toMutableList()
    comps(red, 2).filter { it.tot >= 6 }.forEach { enemies.add(PointF(it.cx, it.cy)) } // barris/cilindros explosivos

    // paredes: célula é tijolo se metade dos pontos tem cor de tijolo (marrom-avermelhado)
    val walls = BooleanArray(cols * rows)
    for (r in 0 until rows) for (c in 0 until cols) {
        var n = 0
        for (j in 0 until 4) for (i in 0 until 4) {
            val x = (a.left + c * CELL + i * 4 + 2).toInt(); val yy = (a.top + r * CELL + j * 4 + 2).toInt()
            if (x >= b.width || yy >= b.height) continue
            val p = b.getPixel(x, yy)
            val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
            if (pr in 110..185 && pg in 48..100 && pb in 25..85 && pr - pg >= 45) n++
        }
        walls[r * cols + c] = n >= 8
    }
    return Detection(shooter, enemies, walls, cols, rows, a.left, a.top, cross)
}
