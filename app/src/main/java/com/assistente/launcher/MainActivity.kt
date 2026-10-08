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
import java.io.File
import java.util.Locale

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
    private var nomeIA = "Aria"
    private var modeloAtual: Modelo = RAPIDO
    private var pediuPerm = false

    private lateinit var face: TextView
    private lateinit var status: TextView
    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var micBtn: Button
    private lateinit var conversaBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var acordarBtn: Button

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun modelo() = File(filesDir, modeloAtual.arquivo)
    private fun parcial() = File(filesDir, modeloAtual.arquivo + ".tmp")
    private fun modeloOk() = modelo().exists() && modelo().length() > 100_000_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nomeIA = prefs.getString("nome", "Aria") ?: "Aria"
        modeloAtual = if (prefs.getString("modelo", "rapido") == "melhor") MELHOR else RAPIDO
        if (prefs.getBoolean("configurado", false)) mostrarChat() else mostrarSetup()
    }

    override fun onResume() {
        super.onResume()
        AssistenteService.ouvinte = AssistenteService.Ouvinte { atualizarUi() }
        if (::chat.isInitialized) {
            if (AssistenteService.instance == null && modeloOk() &&
                !prefs.getBoolean("parado", false) && !DownloadService.running
            ) {
                garantirServico()
            } else {
                atualizarUi()
            }
        }
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
        val tr = prefs.getFloat("tps_rapido", 0f)
        val tm = prefs.getFloat("tps_melhor", 0f)
        val recomendado = when {
            tm > 0f -> if (tm >= 5f) MELHOR else RAPIDO
            tr >= 15f && ram >= 5.0 -> MELHOR
            else -> RAPIDO
        }
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
        val medido = StringBuilder()
        if (tr > 0f) medido.append("Rápido: ").append(String.format(Locale.US, "%.1f", tr)).append(" tokens/s. ")
        if (tm > 0f) medido.append("Melhor: ").append(String.format(Locale.US, "%.1f", tm)).append(" tokens/s. ")
        if (medido.isEmpty()) medido.append("Ainda não medi a velocidade deste telemóvel. Começa pelo Rápido.")
        col.addView(
            texto(
                "RAM: cerca de ${String.format(Locale.US, "%.1f", ram)} GB.\nVelocidade medida: $medido\nRecomendado: ${recomendado.titulo}.",
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
            text = "${MELHOR.titulo}, ${MELHOR.mb} MB. Escreve melhor, mas é bem mais lento."
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
                nomeIA = n
                modeloAtual = m
                prefs.edit()
                    .putString("nome", n)
                    .putString("modelo", m.id)
                    .putBoolean("configurado", true)
                    .putBoolean("parado", false)
                    .apply()
                stopService(Intent(this@MainActivity, AssistenteService::class.java))
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
        conversaBtn = Button(this).apply {
            text = "🎧 Modo conversa: desligado"
            setOnClickListener { AssistenteService.instance?.alternarConversa() }
        }
        acordarBtn = Button(this).apply {
            text = "☀ Acordar $nomeIA"
            visibility = View.GONE
            setOnClickListener {
                prefs.edit().putBoolean("parado", false).apply()
                garantirServico()
            }
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
        micBtn = Button(this).apply {
            text = "🎤"; isEnabled = false
            setOnClickListener { AssistenteService.instance?.tocarMic() }
        }
        send = Button(this).apply {
            text = "Enviar"; isEnabled = false
            setOnClickListener { enviar() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn)
            addView(send)
        }

        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        root.addView(face, LinearLayout.LayoutParams(match, wrap))
        root.addView(nome, LinearLayout.LayoutParams(match, wrap))
        root.addView(status, LinearLayout.LayoutParams(match, wrap))
        root.addView(cfg, LinearLayout.LayoutParams(match, wrap))
        root.addView(conversaBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(acordarBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(downloadBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)

        iniciar()
    }

    private fun abrirSetup() {
        val s = AssistenteService.instance
        if (DownloadService.running || (s != null && (!s.pronto || s.ocupado))) {
            status.text = "Espera um momento (a IA está ocupada ou a descarregar)."
            return
        }
        s?.pararVoz()
        mostrarSetup()
    }

    // ---------- Estado vindo do serviço ----------
    private fun atualizarUi() {
        if (!::chat.isInitialized) return
        val s = AssistenteService.instance
        if (s == null) {
            if (prefs.getBoolean("parado", false) && modeloOk()) {
                face.text = "😴"
                status.text = "$nomeIA está a dormir. Toca em Acordar."
                acordarBtn.visibility = View.VISIBLE
                conversaBtn.text = "🎧 Modo conversa: desligado"
                input.isEnabled = false
                send.isEnabled = false
                micBtn.isEnabled = false
            }
            return
        }
        acordarBtn.visibility = View.GONE
        face.text = s.rostoTxt
        status.text = s.estadoTxt
        val novo = s.chatCompleto()
        if (chat.text.toString() != novo) {
            chat.text = novo
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        input.isEnabled = s.pronto
        send.isEnabled = s.pronto && !s.ocupado
        micBtn.isEnabled = s.pronto && !s.ocupado
        conversaBtn.text = if (s.modoConversa) "🎧 Modo conversa: LIGADO (toca para parar)"
        else "🎧 Modo conversa: desligado"
    }

    private fun enviar() {
        val txt = input.text.toString().trim()
        if (txt.isEmpty()) return
        val s = AssistenteService.instance ?: return
        if (!s.pronto || s.ocupado) return
        input.setText("")
        s.enviarTexto(txt)
    }

    // ---------- Download do modelo ----------
    private fun textoBotao(): String {
        val mb = if (parcial().exists()) parcial().length() / 1_000_000 else 0L
        return if (mb > 0) "Continuar de onde parou" else "Descarregar IA (~${modeloAtual.mb} MB)"
    }

    private val poll = object : Runnable {
        override fun run() {
            if (modeloOk()) {
                garantirServico()
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
        if (modeloOk()) {
            downloadBtn.visibility = View.GONE
            if (AssistenteService.instance != null || prefs.getBoolean("parado", false)) {
                atualizarUi()
            } else {
                garantirServico()
            }
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

    // ---------- Arrancar o serviço do assistente ----------
    private fun garantirServico() {
        if (AssistenteService.instance != null) {
            atualizarUi()
            return
        }
        val faltam = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            faltam.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (faltam.isNotEmpty() && !pediuPerm) {
            pediuPerm = true
            status.text = "A pedir permissões (microfone e notificações)…"
            requestPermissions(faltam.toTypedArray(), 3)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Preciso da permissão do microfone. Ativa em Definições → Apps → Assistente → Permissões."
            return
        }
        try {
            startForegroundService(Intent(this, AssistenteService::class.java))
            status.text = "A acordar $nomeIA…"
            if (!prefs.getBoolean("bateria_pedida", false)) {
                prefs.edit().putBoolean("bateria_pedida", true).apply()
                pedirIsencaoBateria()
            }
        } catch (e: Exception) {
            status.text = "Não consegui iniciar o assistente: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            garantirServico()
        } else {
            pedirIsencaoBateria()
        }
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

    @Deprecated("Launcher não deve fechar com Voltar")
    override fun onBackPressed() {}

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        AssistenteService.ouvinte = null
        super.onDestroy()
    }
}
