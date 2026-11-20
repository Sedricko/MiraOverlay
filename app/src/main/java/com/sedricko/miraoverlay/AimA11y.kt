package com.sedricko.miraoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Só serve para tocar no jogo (onde o dedo soltou a mira). */
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
}
