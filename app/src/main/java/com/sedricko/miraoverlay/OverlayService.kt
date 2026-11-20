package com.sedricko.miraoverlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Button
import android.widget.LinearLayout

/** Janelinha tocável de 120px. Só ela captura toque; o resto da tela passa direto para o jogo. */
@SuppressLint("ViewConstructor")
class Handle(
    ctx: Context, private val wm: WindowManager, private val p: PointF,
    private val color: Int, private val aim: AimView,
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
            aim.invalidate()
        }
        return true
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var aim: AimView
    private lateinit var panel: LinearLayout
    private val wallHandles = mutableListOf<Handle>()
    private val allHandles = mutableListOf<Handle>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()

        aim = AimView(this).apply {
            // valores iniciais baseados no layout do jogo; arraste os pontos verdes/amarelos para ajustar cada fase
            origin.set(w * .21f, h * .715f); target.set(w * .78f, h * .72f)
            areaA.set(w * .081f, h * .19f); areaB.set(w * .919f, h * .771f)
            blockA.set(w * .333f, h * .617f); blockB.set(w * .667f, h * .771f)
        }
        // NOT_TOUCHABLE: o jogo abaixo recebe todos os toques
        wm.addView(aim, LP(LP.MATCH_PARENT, LP.MATCH_PARENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))

        wallHandles += Handle(this, wm, aim.areaA, 0xFF34C759.toInt(), aim)
        wallHandles += Handle(this, wm, aim.areaB, 0xFF34C759.toInt(), aim)
        wallHandles += Handle(this, wm, aim.blockA, 0xFFFFCC00.toInt(), aim)
        wallHandles += Handle(this, wm, aim.blockB, 0xFFFFCC00.toInt(), aim)
        allHandles += wallHandles
        allHandles += Handle(this, wm, aim.origin, 0xFF0A84FF.toInt(), aim)
        allHandles += Handle(this, wm, aim.target, 0xFFFF3B30.toInt(), aim)

        val wallsBtn = Button(this).apply { text = "Paredes" }
        val bounceBtn = Button(this).apply { text = "Ric: ${aim.bounces}" }
        val closeBtn = Button(this).apply { text = "X" }
        wallsBtn.setOnClickListener {
            val v = if (wallHandles[0].visibility == View.VISIBLE) View.GONE else View.VISIBLE
            wallHandles.forEach { it.visibility = v }
        }
        bounceBtn.setOnClickListener {
            aim.bounces = if (aim.bounces >= 8) 0 else aim.bounces + 1
            bounceBtn.text = "Ric: ${aim.bounces}"
        }
        closeBtn.setOnClickListener { stopSelf() }

        panel = LinearLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(wallsBtn); addView(bounceBtn); addView(closeBtn)
        }
        wm.addView(panel, LP(LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 330 })
    }

    override fun onDestroy() {
        allHandles.forEach { wm.removeView(it) }
        wm.removeView(aim)
        wm.removeView(panel)
    }
}
