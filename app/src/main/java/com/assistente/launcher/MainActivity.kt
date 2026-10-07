package com.assistente.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import kotlin.concurrent.thread

private const val NOME = "Aria"

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var llm: LlmInference? = null
    private var busy = false
    private var carregando = false
    private val history = mutableListOf<Pair<String, String>>()

    private lateinit var face: TextView
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var downloadBtn: Button

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun modelo() = File(filesDir, DownloadService.MODEL_FILE)
    private fun parcial() = File(filesDir, DownloadService.MODEL_FILE + ".tmp")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1A1A2E"))
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }

        face = TextView(this).apply {
            text = "🙂"; textSize = 64f; gravity = Gravity.CENTER
        }
        val nome = TextView(this).apply {
            text = NOME; textSize = 22f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#A0A0C0"))
            gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        downloadBtn = Button(this).apply {
            text = "Descarregar IA (~550 MB)"; visibility = View.GONE
            setOnClickListener { descarregar() }
        }
        chat = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        scroll = ScrollView(this).apply { addView(chat) }
        input = EditText(this).apply {
            hint = "Escreve aqui…"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            isEnabled = false; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        send = Button(this).apply {
            text = "Enviar"; isEnabled = false
            setOnClickListener { enviar() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }

        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        root.addView(face, LinearLayout.LayoutParams(match, wrap))
        root.addView(nome, LinearLayout.LayoutParams(match, wrap))
        root.addView(status, LinearLayout.LayoutParams(match, wrap))
        root.addView(downloadBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)

        iniciar()
    }

    private val poll = object : Runnable {
        override fun run() {
            val f = modelo()
            if (f.exists() && f.length() > 100_000_000L) {
                carregarModelo(f)
                return
            }
            val tmp = parcial()
            val mb = if (tmp.exists()) tmp.length() / 1_000_000 else 0L
            if (DownloadService.running) {
                val tot = DownloadService.total
                val progresso = if (tot > 0) "${tmp.length() * 100 / tot}%" else "$mb MB"
                status.text = "A descarregar… $progresso\n${DownloadService.message}"
                ui.postDelayed(this, 1000)
            } else {
                status.text = "Download interrompido ($mb MB guardados)\n${DownloadService.message}"
                downloadBtn.text = if (mb > 0) "Continuar de onde parou" else "Descarregar IA (~550 MB)"
                downloadBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun iniciar() {
        val f = modelo()
        if (f.exists() && f.length() > 100_000_000L) {
            carregarModelo(f)
        } else if (DownloadService.running) {
            downloadBtn.visibility = View.GONE
            ui.post(poll)
        } else {
            val mb = if (parcial().exists()) parcial().length() / 1_000_000 else 0L
            status.text = if (mb > 0) "Download parcial: $mb MB guardados."
            else "Falta descarregar o cérebro da IA (~550 MB). Usa Wi-Fi."
            downloadBtn.text = if (mb > 0) "Continuar de onde parou" else "Descarregar IA (~550 MB)"
            downloadBtn.visibility = View.VISIBLE
        }
    }

    private fun descarregar() {
        downloadBtn.visibility = View.GONE
        status.text = "A iniciar download…"
        try {
            DownloadService.running = true
            startForegroundService(Intent(this, DownloadService::class.java))
        } catch (e: Exception) {
            DownloadService.running = false
            status.text = "Não consegui iniciar o download: ${e.message}"
            downloadBtn.visibility = View.VISIBLE
            return
        }
        ui.postDelayed(poll, 1000)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            pedirIsencaoBateria()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pedirIsencaoBateria()
    }

    private fun pedirIsencaoBateria() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun carregarModelo(f: File) {
        if (carregando || llm != null) return
        carregando = true
        downloadBtn.visibility = View.GONE
        status.text = "A acordar a $NOME…"
        thread {
            try {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                val m = LlmInference.createFromOptions(this, opts)
                ui.post {
                    llm = m
                    status.text = "Pronta (offline) ✅"
                    input.isEnabled = true
                    send.isEnabled = true
                }
            } catch (e: Throwable) {
                ui.post {
                    carregando = false
                    status.text = "Erro ao carregar: ${e.message}"
                }
            }
        }
    }

    private fun montarPrompt(txt: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\nTu és $NOME, assistente e amigo do utilizador. ")
        sb.append("Responde sempre em português de Portugal, com frases curtas e simpáticas.<|im_end|>\n")
        history.takeLast(6).forEach { (r, t) ->
            sb.append("<|im_start|>$r\n${t.take(300)}<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n$txt<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun add(quem: String, texto: String) {
        chat.append("$quem: $texto\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun enviar() {
        val m = llm ?: return
        val txt = input.text.toString().trim()
        if (txt.isEmpty() || busy) return
        input.setText("")
        busy = true
        send.isEnabled = false
        add("Tu", txt)
        face.text = "🤔"
        status.text = "$NOME está a pensar…"
        val prompt = montarPrompt(txt)
        thread {
            val resp = try {
                m.generateResponse(prompt).replace("<|im_end|>", "").trim()
            } catch (e: Throwable) {
                "Erro: ${e.message}"
            }
            ui.post {
                history.add("user" to txt)
                history.add("assistant" to resp)
                add(NOME, resp)
                face.text = "🙂"
                status.text = "Pronta (offline) ✅"
                busy = false
                send.isEnabled = true
            }
        }
    }

    @Deprecated("Launcher não deve fechar com Voltar")
    override fun onBackPressed() {}

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        llm?.close()
        super.onDestroy()
    }
}
