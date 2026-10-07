package com.assistente.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = TextView(this)
        t.text = "Olá! Eu sou o teu assistente 👋"
        t.textSize = 24f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setBackgroundColor(Color.parseColor("#1A1A2E"))
        setContentView(t)
    }
}
