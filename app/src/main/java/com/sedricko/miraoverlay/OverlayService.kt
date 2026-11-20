package com.sedricko.miraoverlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

/** Janelinha tocável de 120px para arrastar um ponto (atirador ou canto de parede). */
@SuppressLint("ViewConstructor")
class Handle(
    ctx: Context, private val wm: WindowManager, private val p: PointF,
    color: Int, private val onMove: () -> Unit,
) : View(ctx) {
    private val half = 60
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; this.color = color }
    val lp = LP(half * 2, half * 2, LP.TYPE_APPLICATION_OVERLAY,
        LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (p.x - half).toInt(); y = (p.y - half).toInt() }

    init { wm.addView(this, lp) }

    override fun onDraw(c: Canvas) { c.drawCircle(half.toFloat(), half.toFloat(), 40f, paint) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            p.set(e.rawX, e.rawY)
            lp.x = (p.x - half).toInt(); lp.y = (p.y - half).toInt()
            wm.updateViewLayout(this, lp)
            onMove()
        }
        return true
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var aim: AimView
    private lateinit var aimParams: LP
    private lateinit var panel: LinearLayout
    private val wallHandles = mutableListOf<Handle>()
    private val allHandles = mutableListOf<Handle>()
    private val ui = Handler(Looper.getMainLooper())
    private val base = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS

    override fun onBind(i: Intent?): IBinder? = null

    /** A janela da mira acompanha a área de jogo; o resto da tela fica livre para o jogo. */
    private fun syncWindow() {
        aimParams.x = min(aim.areaA.x, aim.areaB.x).toInt(); aimParams.y = min(aim.areaA.y, aim.areaB.y).toInt()
        aimParams.width = abs(aim.areaA.x - aim.areaB.x).toInt().coerceAtLeast(1)
        aimParams.height = abs(aim.areaA.y - aim.areaB.y).toInt().coerceAtLeast(1)
        aim.offX = aimParams.x.toFloat(); aim.offY = aimParams.y.toFloat()
        wm.updateViewLayout(aim, aimParams)
        aim.invalidate()
    }

    private var shooting = false
    private var misses = 0
    private val scan = object : Runnable {
        override fun run() {
            if (aim.autoMode && aim.visibility == View.VISIBLE && !shooting) AimA11y.instance?.snap { bmp ->
                if (bmp != null) {
                    val a = android.graphics.RectF(minOf(aim.areaA.x, aim.areaB.x), minOf(aim.areaA.y, aim.areaB.y),
                        maxOf(aim.areaA.x, aim.areaB.x), maxOf(aim.areaA.y, aim.areaB.y))
                    val found = findTargets(bmp, a, aim.origin)
                    bmp.recycle()
                    // mantém a última trajetória travada; só limpa depois de 3 prints seguidos sem inimigo
                    misses = if (found.isEmpty()) misses + 1 else 0
                    if (found.isNotEmpty() || misses >= 3) aim.targets = found
                    aim.invalidate()
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun shoot(x: Float, y: Float) {
        val s = AimA11y.instance
        if (s == null) {
            Toast.makeText(this, "Ative \"Mira Overlay\" em Acessibilidade", Toast.LENGTH_LONG).show()
            return
        }
        shooting = true
        // a mira precisa sair do caminho, senão o toque cairia nela mesma
        aimParams.flags = base or LP.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(aim, aimParams)
        ui.postDelayed({
            s.tap(x, y) {
                aimParams.flags = if (aim.autoMode) base or LP.FLAG_NOT_TOUCHABLE else base
                wm.updateViewLayout(aim, aimParams)
                // durante o voo do tiro o inimigo reage; não reanalisa por 2 s
                ui.postDelayed({ shooting = false }, 2000)
            }
        }, 60)
    }

    override fun onCreate() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()

        aim = AimView(this).apply {
            // valores iniciais baseados no layout do jogo; arraste os círculos para ajustar cada fase
            origin.set(w * .21f, h * .715f)
            areaA.set(w * .081f, h * .19f); areaB.set(w * .919f, h * .771f)
            blockA.set(w * .333f, h * .617f); blockB.set(w * .667f, h * .771f)
            onShoot = { x, y -> shoot(x, y) }
        }
        aimParams = LP(1, 1, LP.TYPE_APPLICATION_OVERLAY, base or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(aim, aimParams)
        syncWindow()

        val sync = { syncWindow() }
        wallHandles += Handle(this, wm, aim.areaA, 0xFF34C759.toInt(), sync)
        wallHandles += Handle(this, wm, aim.areaB, 0xFF34C759.toInt(), sync)
        wallHandles += Handle(this, wm, aim.blockA, 0xFFFFCC00.toInt(), sync)
        wallHandles += Handle(this, wm, aim.blockB, 0xFFFFCC00.toInt(), sync)
        allHandles += wallHandles
        allHandles += Handle(this, wm, aim.origin, 0xFF0A84FF.toInt(), sync)

        fun btn(t: String) = Button(this).apply { text = t; textSize = 12f; minWidth = 0; minimumWidth = 0 }
        val wallsBtn = btn("Paredes")
        val bounceBtn = btn("Ric: ${aim.bounces}")
        val autoBtn = btn("Guia")
        val onOffBtn = btn("On/Off")
        val closeBtn = btn("X")
        autoBtn.setOnClickListener {
            aim.autoMode = !aim.autoMode
            autoBtn.text = if (aim.autoMode) "Guia" else "Manual"
            aimParams.flags = if (aim.autoMode) base or LP.FLAG_NOT_TOUCHABLE else base
            wm.updateViewLayout(aim, aimParams)
            aim.invalidate()
        }
        wallsBtn.setOnClickListener {
            val v = if (wallHandles[0].visibility == View.VISIBLE) View.GONE else View.VISIBLE
            wallHandles.forEach { it.visibility = v }
        }
        bounceBtn.setOnClickListener {
            aim.bounces = if (aim.bounces >= 8) 0 else aim.bounces + 1
            bounceBtn.text = "Ric: ${aim.bounces}"
        }
        onOffBtn.setOnClickListener { // desligado: nada na tela captura toque (menus, vitória, etc.)
            val v = if (aim.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            aim.visibility = v; allHandles.forEach { it.visibility = v }
        }
        closeBtn.setOnClickListener { stopSelf() }

        panel = LinearLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(wallsBtn); addView(bounceBtn); addView(autoBtn); addView(onOffBtn); addView(closeBtn)
        }
        wm.addView(panel, LP(LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 330 })
        ui.post(scan)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        allHandles.forEach { wm.removeView(it) }
        wm.removeView(aim)
        wm.removeView(panel)
    }
}
