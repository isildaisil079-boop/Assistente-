package com.assistente.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

class VozActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = getSharedPreferences("cfg", MODE_PRIVATE).getString("idioma", "pt-PT") ?: "pt-PT"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fala agora…")
        }
        try {
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            AssistenteService.instance?.vozErro("Este telemóvel não tem ecrã de ditado por voz.")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val txt = if (resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim().orEmpty()
        } else ""
        if (txt.isNotEmpty()) {
            AssistenteService.instance?.processarVoz(txt)
        } else {
            AssistenteService.instance?.vozErro("Não ouvi nada. Toca em 🎤 para tentar de novo.")
        }
        finish()
    }
}
