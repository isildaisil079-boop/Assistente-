package com.assistente.launcher

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import kotlin.concurrent.thread

class Modelo(
    val id: String,
    val titulo: String,
    val arquivo: String,
    val url: String,
    val mb: Int
)

val RAPIDO = Modelo(
    "rapido", "Rápido (0,5B)", "modelo.task",
    "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
    550
)
val MELHOR = Modelo(
    "melhor", "Melhor (1,5B)", "modelo_melhor.task",
    "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
    1600
)

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private var llm: LlmInference? = null
    private var busy = false
    private var carregando = false
    private var nomeIA = "Aria"
    private var modeloAtual: Modelo = RAPIDO
    private val history = mutableListOf<Pair<String, String>>()

    private lateinit var face: TextView
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var downloadBtn: Button

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun modelo() = File(filesDir, modeloAtual.arquivo)
    private fun parcial() = File(filesDir, modeloAtual.arquivo + ".tmp")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nomeIA = prefs.getString("nome", "Aria") ?: "Aria"
        modeloAtual = if (prefs.getString("modelo", "rapido") == "melhor") MELHOR else RAPIDO
        if (prefs.getBoolean("configurado", false)) mostrarChat() else mostrarSetup()
    }

    private fun novoRoot(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1A1A2E"))
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
        return root
    }

    private fun texto(t: String, size: Float, cor: String = "#FFFFFF") = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.parseColor(cor))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun ramTotalGb(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1_000_000_000.0
    }

    // ---------- Ecrã de setup ----------
    private fun mostrarSetup() {
        ui.removeCallbacks(poll)
        val root = novoRoot()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(24), dp(20), dp(24))

        val ram = ramTotalGb()
        val recomendado = if (ram >= 5.0) MELHOR else RAPIDO
        val jaConfigurado = prefs.getBoolean("configurado", false)

        col.addView(texto("Vamos criar o teu assistente", 22f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        col.addView(texto("Nome do assistente:", 14f, "#A0A0C0"))
        val nomeEdit = EditText(this).apply {
            setText(nomeIA)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        col.addView(nomeEdit)

        col.addView(texto("Modelo de IA:", 14f, "#A0A0C0"))
        col.addView(
            texto(
                "O teu telemóvel tem cerca de ${String.format("%.1f", ram)} GB de RAM. Recomendado: ${recomendado.titulo}.",
                13f, "#C0C0E0"
            )
        )
        val grupo = RadioGroup(this)
        val rb1 = RadioButton(this).apply {
            id = View.generateViewId()
            text = "${RAPIDO.titulo}, ${RAPIDO.mb} MB. Mais rápido, respostas mais simples."
            setTextColor(Color.WHITE)
        }
        val rb2 = RadioButton(this).apply {
            id = View.generateViewId()
            text = "${MELHOR.titulo}, ${MELHOR.mb} MB. Escreve melhor, mas é mais lento."
            setTextColor(Color.WHITE)
        }
        grupo.addView(rb1)
        grupo.addView(rb2)
        val escolhido = if (jaConfigurado) modeloAtual else recomendado
        val marcado = if (escolhido.id == "melhor") rb2 else rb1
        marcado.isChecked = true
        col.addView(grupo)

        col.addView(texto("Podes mudar isto mais tarde.", 12f, "#808098"))

        val ok = Button(this).apply {
            text = "Continuar"
            setOnClickListener {
                val n = nomeEdit.text.toString().trim().ifEmpty { "Aria" }
                val m = if (rb2.isChecked) MELHOR else RAPIDO
                llm?.close()
                llm = null
                carregando = false
                history.clear()
                nomeIA = n
                modeloAtual = m
                prefs.edit()
                    .putString("nome", n)
                    .putString("modelo", m.id)
                    .putBoolean("configurado", true)
                    .apply()
                mostrarChat()
            }
        }
        col.addView(ok)

        root.addView(
            ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    // ---------- Ecrã de chat ----------
    private fun mostrarChat() {
        val root = novoRoot()

        face = TextView(this).apply {
            text = "🙂"; textSize = 64f; gravity = Gravity.CENTER
        }
        val nome = TextView(this).apply {
            text = nomeIA; textSize = 22f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#A0A0C0"))
            gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val cfg = TextView(this).apply {
            text = "⚙ Mudar nome ou modelo"; textSize = 12f
            setTextColor(Color.parseColor("#7C7CFF"))
            gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(8))
            setOnClickListener { abrirSetup() }
        }
        downloadBtn = Button(this).apply {
            visibility = View.GONE
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
        root.addView(cfg, LinearLayout.LayoutParams(match, wrap))
        root.addView(downloadBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)

        iniciar()
    }

    private fun abrirSetup() {
        if (busy || DownloadService.running) {
            status.text = "Espera um momento (a IA está ocupada ou a descarregar)."
            return
        }
        mostrarSetup()
    }

    private fun textoBotao(): String {
        val mb = if (parcial().exists()) parcial().length() / 1_000_000 else 0L
        return if (mb > 0) "Continuar de onde parou" else "Descarregar IA (~${modeloAtual.mb} MB)"
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
                downloadBtn.text = textoBotao()
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
            else "Falta descarregar o cérebro da IA (~${modeloAtual.mb} MB). Usa Wi-Fi."
            downloadBtn.text = textoBotao()
            downloadBtn.visibility = View.VISIBLE
        }
    }

    private fun descarregar() {
        downloadBtn.visibility = View.GONE
        status.text = "A iniciar download…"
        try {
            DownloadService.running = true
            val i = Intent(this, DownloadService::class.java)
            i.putExtra("url", modeloAtual.url)
            i.putExtra("file", modeloAtual.arquivo)
            startForegroundService(i)
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
        status.text = "A acordar $nomeIA…"
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
        sb.append("<|im_start|>system\nTu és $nomeIA, assistente e amigo do utilizador. ")
        sb.append("Responde sempre em português de Portugal, com frases curtas, completas e simpáticas. ")
        sb.append("Usa pontuação e letras maiúsculas corretas. Não uses emojis nem símbolos estranhos.<|im_end|>\n")
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
        status.text = "$nomeIA está a pensar…"
        val prompt = montarPrompt(txt)
        val nomeNaHora = nomeIA
        thread {
            val resp = try {
                val so = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(0.4f)
                    .build()
                val s = LlmInferenceSession.createFromOptions(m, so)
                try {
                    s.addQueryChunk(prompt)
                    s.generateResponse().replace("<|im_end|>", "").trim()
                } finally {
                    s.close()
                }
            } catch (e: Throwable) {
                "Erro: ${e.message}"
            }
            ui.post {
                history.add("user" to txt)
                history.add("assistant" to resp)
                add(nomeNaHora, resp)
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
