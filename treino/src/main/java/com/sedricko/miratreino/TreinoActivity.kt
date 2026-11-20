package com.sedricko.miratreino

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/** App de treino: o simulador (HTML) roda offline dentro de uma WebView. */
class TreinoActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.setBackgroundColor(0xFF15110F.toInt())
        w.loadUrl("file:///android_asset/index.html")
        setContentView(w)
    }
}
