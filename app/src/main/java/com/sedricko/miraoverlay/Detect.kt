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
fun findTargets(b: Bitmap, a: RectF, oldShooter: PointF): Detection? {
    // varre a tela toda: a mira dourada costuma ficar FORA da área (dedo abaixo da arena). Fora da área só conta
    // o dourado, e só na faixa do meio (o topo e a base têm balas/botões dourados do jogo).
    val cols = (b.width / CELL) + 1; val rows = (b.height / CELL) + 1
    val dark = IntArray(cols * rows); val gold = IntArray(cols * rows); val red = IntArray(cols * rows)
    val bandT = b.height * 0.18f; val bandB = b.height * 0.80f
    var y = 6
    while (y < b.height) {
        var x = 6
        while (x < b.width) {
            val inA = x > a.left + 6 && x < a.right - 6 && y > a.top + 6 && y < a.bottom - 6
            if (inA || (y > bandT && y < bandB)) {
                val p = b.getPixel(x, y)
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bb = p and 255
                val i = (y / CELL) * cols + (x / CELL)
                if (inA && max(max(r, g), bb) < 75) dark[i]++
                if (r > 190 && g in 140..215 && bb < 120) gold[i]++
                if (inA && r > 170 && g < 80 && bb < 80) red[i]++
            }
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
            out.add(Cl(minC * CELL.toFloat(), minR * CELL.toFloat(), (maxC + 1) * CELL.toFloat(), (maxR + 1) * CELL.toFloat(),
                tot, (sx / tot + .5f) * CELL, (sy / tot + .5f) * CELL))
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
    // atiradora = cabelo loiro perto de uma mancha escura e com roupa azul embaixo;
    // se a roupa não aparecer bem, vale o dourado mais perto de onde ela estava (ela anda em plataforma móvel)
    val golds = comps(gold, 1)
    val nearDark = golds.filter { h ->
        h.tot >= 6 && darks.any { abs(h.cx - (it.l + it.r) / 2) < it.w / 2 + 100 && h.cy > it.t - 100 && h.cy < it.b + 100 }
    }
    val hair = nearDark.filter { blueBelow(it.cx, it.cy) >= 14 }.maxByOrNull { it.tot }
        ?: nearDark.filter { hypot(it.cx - oldShooter.x, it.cy + 8f - oldShooter.y) < 120f }
            .minByOrNull { hypot(it.cx - oldShooter.x, it.cy + 8f - oldShooter.y) }
    // y: altura do cano em relação ao cabelo
    val shooter = hair?.let { PointF(it.cx, it.cy + 8f) } // sem cabelo na tela: não move a atiradora
    val ref = shooter ?: oldShooter // quem NÃO é inimigo nem mira, mesmo se a atiradora sumir por um instante
    // mira do jogo: outro dourado grande, longe da atiradora (o brilho do tiro também é dourado, mas dura pouco)
    val cross = golds.filter { it !== hair && it.tot >= 20 && it.w in 48f..160f && it.h in 48f..160f &&
        hypot(it.cx - ref.x, it.cy - ref.y) > 100f }.maxByOrNull { it.tot }?.let { PointF(it.cx, it.cy) }

    val enemies = darks.filter { d ->
        d.h >= 80 && d.h >= 1.1f * d.w && // descarta corpo caído e manchas pequenas
            !(abs(ref.x - (d.l + d.r) / 2) < d.w / 2 + 100 && ref.y > d.t - 100 && ref.y < d.b + 100)
    }.map { PointF((it.l + it.r) / 2, it.t + it.h * 0.4f) }.toMutableList()
    comps(red, 2).filter { it.tot >= 6 }.forEach { enemies.add(PointF(it.cx, it.cy)) } // barris/cilindros explosivos

    // paredes: célula é tijolo se metade dos pontos tem cor de tijolo (marrom-avermelhado)
    // a grade passa um pouco da área: a moldura de tijolo do cenário costuma ficar logo fora dela
    val wl = maxOf(0f, a.left - 64f); val wt = maxOf(0f, a.top - 64f)
    val wCols = ((minOf(b.width.toFloat(), a.right + 64f) - wl) / CELL).toInt() + 1
    val wRows = ((minOf(b.height.toFloat(), a.bottom + 64f) - wt) / CELL).toInt() + 1
    val walls = BooleanArray(wCols * wRows)
    for (r in 0 until wRows) for (c in 0 until wCols) {
        var n = 0
        for (j in 0 until 4) for (i in 0 until 4) {
            val x = (wl + c * CELL + i * 4 + 2).toInt(); val yy = (wt + r * CELL + j * 4 + 2).toInt()
            if (x >= b.width || yy >= b.height) continue
            val p = b.getPixel(x, yy)
            val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
            if (pr in 110..185 && pg in 48..100 && pb in 25..85 && pr - pg >= 45) n++
        }
        walls[r * wCols + c] = n >= 8
    }
    return Detection(shooter, enemies, walls, wCols, wRows, wl, wt, cross)
}
