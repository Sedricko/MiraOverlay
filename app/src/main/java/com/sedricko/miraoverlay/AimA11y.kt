package com.sedricko.miraoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** Toca no jogo (atirar) e tira print da tela (achar o inimigo). */
class AimA11y : AccessibilityService() {
    companion object { var instance: AimA11y? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(i: Intent?): Boolean { instance = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float, done: () -> Unit) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 150)).build()
        val cb = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = done()
            override fun onCancelled(d: GestureDescription?) = done()
        }
        if (!dispatchGesture(g, cb, null)) done()
    }

    /** Print da tela inteira (Android 11+); devolve null se falhar. */
    fun snap(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                cb(bmp)
            }
            override fun onFailure(errorCode: Int) = cb(null)
        })
    }
}
