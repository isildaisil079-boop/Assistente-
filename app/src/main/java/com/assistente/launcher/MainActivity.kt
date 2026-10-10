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
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
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

val IDIOMAS = listOf(
    "pt-PT" to "Português (Portugal)",
    "pt-BR" to "Português (Brasil)",
    "en-US" to "English",
    "es-ES" to "Español",
    "fr-FR" to "Français"
)

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private var nomeIA = "Aria"
    private var modeloAtual: Modelo = RAPIDO
    private var pediuPerm = false
    private var tela = ""

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
    private fun modeloInstalado(m: Modelo): Boolean {
        val f = File(filesDir, m.arquivo)
        return f.exists() && f.length() > 100_000_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nomeIA = prefs.getString("nome", "Aria") ?: "Aria"
        modeloAtual = if (prefs.getString("modelo", "rapido") == "melhor") MELHOR else RAPIDO
        if (prefs.getBoolean("configurado", false)) mostrarChat() else mostrarConfig(true)
    }

    override fun onResume() {
        super.onResume()
        AssistenteService.ouvinte = AssistenteService.Ouvinte { atualizarUi() }
        if (tela == "chat" && ::chat.isInitialized) {
            if (AssistenteService.instance == null && modeloOk() &&
                !prefs.getBoolean("parado", false) && !DownloadService.running
            ) {
                garantirServico()
            } else {
                atualizarUi()
            }
        }
    }

    // ---------- Componentes comuns ----------
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

    private fun titulo(t: String) = texto(t, 22f).apply {
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
    }

    private fun secao(t: String) = texto(t, 16f, "#9FA8FF").apply {
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun nota(t: String) = texto(t, 12f, "#9090A8")

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { acao() }
    }

    private fun radios(opcoes: List<String>, marcado: Int): Pair<RadioGroup, List<RadioButton>> {
        val g = RadioGroup(this)
        val rbs = opcoes.mapIndexed { i, t ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = t
                setTextColor(Color.WHITE)
                isChecked = (i == marcado)
            }
        }
        rbs.forEach { g.addView(it) }
        return g to rbs
    }

    private fun ramTotalGb(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / 1_000_000_000.0
    }

    // ---------- Configurações ----------
    private fun mostrarConfig(primeiraVez: Boolean) {
        tela = "config"
        ui.removeCallbacks(poll)
        val s = AssistenteService.instance
        s?.pararVoz()
        val root = novoRoot()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(28))

        col.addView(titulo(if (primeiraVez) "Vamos criar o teu assistente" else "⚙ Configurações"))

        // Assistente
        col.addView(secao("Assistente"))
        col.addView(nota("Nome:"))
        val nomeEdit = EditText(this).apply {
            setText(nomeIA)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        col.addView(nomeEdit)

        // Idioma
        col.addView(secao("Idioma da conversa"))
        val idiomaAtual = prefs.getString("idioma", "pt-PT") ?: "pt-PT"
        val (gI, rbI) = radios(
            IDIOMAS.map { it.second },
            IDIOMAS.indexOfFirst { it.first == idiomaAtual }.coerceAtLeast(0)
        )
        col.addView(gI)
        col.addView(
            nota(
                "O idioma muda a voz, o reconhecimento e a língua das respostas da IA. " +
                    "Os comandos (alarmes, notas…) e os menus estão, por agora, só em português."
            )
        )

        // Modelo de IA
        col.addView(secao("Modelo de IA (o cérebro)"))
        val ram = ramTotalGb()
        val tr = prefs.getFloat("tps_rapido", 0f)
        val tm = prefs.getFloat("tps_melhor", 0f)
        val recomendado = when {
            tm > 0f -> if (tm >= 5f) MELHOR else RAPIDO
            tr >= 15f && ram >= 5.0 -> MELHOR
            else -> RAPIDO
        }
        val medido = StringBuilder()
        if (tr > 0f) medido.append("Rápido: ").append(String.format(Locale.US, "%.1f", tr)).append(" tokens/s. ")
        if (tm > 0f) medido.append("Melhor: ").append(String.format(Locale.US, "%.1f", tm)).append(" tokens/s. ")
        if (medido.isEmpty()) medido.append("ainda não medida.")
        col.addView(
            nota(
                "RAM: cerca de ${String.format(Locale.US, "%.1f", ram)} GB. " +
                    "Velocidade medida: $medido Recomendado: ${recomendado.titulo}."
            )
        )
        val modelos = listOf(RAPIDO, MELHOR)
        val jaConfigurado = prefs.getBoolean("configurado", false)
        val marcadoM = if (jaConfigurado) (if (modeloAtual.id == "melhor") 1 else 0)
        else (if (recomendado.id == "melhor") 1 else 0)
        val (gM, rbM) = radios(
            modelos.map {
                "${it.titulo} · ${it.mb} MB · " + if (modeloInstalado(it)) "instalado ✅" else "por descarregar"
            },
            marcadoM
        )
        col.addView(gM)
        col.addView(nota("Se escolheres um modelo por descarregar, o botão de download aparece no ecrã principal."))

        val msgTv = nota("")

        // Voz
        col.addView(secao("Voz"))
        val infoTv = texto(
            s?.infoVoz() ?: "Acorda a Aria (ecrã principal) para ver o estado da voz.",
            12f, "#C0C0E0"
        )
        col.addView(infoTv)

        col.addView(texto("Ouvidos (reconhecimento de voz)", 14f, "#FFFFFF").apply {
            setTypeface(null, Typeface.BOLD)
        })
        val varAtual = prefs.getString("whisper_var", "base") ?: "base"
        val wB = TranscritorWhisper(applicationContext).also { it.variante = "base" }
        val wS = TranscritorWhisper(applicationContext).also { it.variante = "small" }
        val (gV, rbV) = radios(
            listOf(
                "Base · 160 MB · rápida, boa precisão · " + if (wB.instalado()) "instalada ✅" else "por descarregar",
                "Small · 375 MB · mais precisa, mais lenta · " + if (wS.instalado()) "instalada ✅" else "por descarregar"
            ),
            if (varAtual == "small") 1 else 0
        )
        col.addView(gV)
        val chkProp = CheckBox(this).apply {
            text = "Preferir a voz própria (100% offline)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("whisper_pref", false)
        }
        col.addView(chkProp)
        col.addView(botao("⬇ Descarregar a voz própria escolhida") {
            if (s == null) {
                msgTv.text = "A Aria está a dormir. Volta ao ecrã principal e toca em Acordar."
                return@botao
            }
            val v = if (rbV[1].isChecked) "small" else "base"
            prefs.edit().putString("whisper_var", v).apply()
            s.recarregarConfig()
            msgTv.text = s.iniciarDownloadVoz()
        })
        col.addView(botao("🔁 Tentar a voz do sistema outra vez") {
            if (s == null) {
                msgTv.text = "A Aria está a dormir. Volta ao ecrã principal e toca em Acordar."
                return@botao
            }
            s.tentarSistemaOutraVez()
            msgTv.text = "Combinado. Toca em 🎤 no ecrã principal para testar."
        })

        col.addView(texto("Boca (voz de resposta)", 14f, "#FFFFFF").apply {
            setTypeface(null, Typeface.BOLD)
        })
        col.addView(botao("🔊 Testar a voz") {
            if (s == null) {
                msgTv.text = "A Aria está a dormir. Volta ao ecrã principal e toca em Acordar."
                return@botao
            }
            s.testarVoz()
            msgTv.text = "A tocar a frase de teste. Se não ouvires nada, usa os botões abaixo."
        })
        col.addView(botao("⚙ Abrir definições de voz do telemóvel") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    msgTv.text = "Não consegui abrir as definições."
                }
            }
        })
        col.addView(botao("⬇ Instalar dados de voz") {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e: Exception) {
                msgTv.text = "Este telemóvel não tem instalador de dados de voz. " +
                    "Usa as definições de voz e escolhe o motor da Google."
            }
        })
        col.addView(msgTv)

        // Guardar / cancelar
        col.addView(botao(if (primeiraVez) "Continuar" else "Guardar") {
            val nomeNovo = nomeEdit.text.toString().trim().ifEmpty { "Aria" }
            val idiomaNovo = IDIOMAS[rbI.indexOfFirst { it.isChecked }.coerceAtLeast(0)].first
            val modeloNovo = if (rbM[1].isChecked) MELHOR else RAPIDO
            val varNova = if (rbV[1].isChecked) "small" else "base"
            val mudouServico = modeloNovo.id != modeloAtual.id || nomeNovo != nomeIA
            prefs.edit()
                .putString("nome", nomeNovo)
                .putString("idioma", idiomaNovo)
                .putString("modelo", modeloNovo.id)
                .putString("whisper_var", varNova)
                .putBoolean("whisper_pref", chkProp.isChecked)
                .putBoolean("configurado", true)
                .putBoolean("parado", false)
                .apply()
            nomeIA = nomeNovo
            modeloAtual = modeloNovo
            mostrarChat()
            if (!primeiraVez && mudouServico) {
                reiniciarServico()
            } else {
                AssistenteService.instance?.recarregarConfig()
            }
        })
        if (!primeiraVez) col.addView(botao("Cancelar") { mostrarChat() })

        root.addView(
            ScrollView(this).apply { addView(col) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    private fun reiniciarServico() {
        stopService(Intent(this, AssistenteService::class.java))
        var tentativas = 0
        val r = object : Runnable {
            override fun run() {
                if (AssistenteService.instance == null) {
                    if (modeloOk()) garantirServico() else iniciar()
                } else if (tentativas++ < 20) {
                    ui.postDelayed(this, 250)
                }
            }
        }
        ui.postDelayed(r, 300)
    }

    // ---------- Ajuda ----------
    private fun mostrarInfo() {
        tela = "info"
        ui.removeCallbacks(poll)
        AssistenteService.instance?.pararVoz()
        val root = novoRoot()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(20), dp(20), dp(28))

        col.addView(titulo("ℹ Ajuda: o que a $nomeIA faz"))
        col.addView(
            texto(
                "A $nomeIA é um assistente de IA que vive no teu telemóvel e também serve de ecrã inicial. " +
                    "Funciona offline: o “cérebro” é descarregado uma vez e depois não precisa de internet.",
                14f
            )
        )

        col.addView(secao("Como falar com ela"))
        col.addView(
            texto(
                "• Texto: escreve e toca em Enviar.\n" +
                    "• 🎤: toca, espera o bip e fala. Toca outra vez para terminar mais cedo.\n" +
                    "• 🎧 Modo conversa: ela ouve, responde em voz alta e volta a ouvir sozinha. " +
                    "Pára depois de 3 silêncios seguidos.\n" +
                    "• Ecrã apagado: a notificação “${nomeIA} ativa” tem os botões 🎤 Falar e ⏹ Parar. " +
                    "O botão do auricular também tenta ativá-la, mas depende do telemóvel.",
                14f
            )
        )

        col.addView(secao("O que já faz (resposta imediata, sem IA)"))
        col.addView(
            texto(
                "• Horas e data: “Que horas são?”, “Que dia é hoje?”\n" +
                    "• Notas: “Anota comprar pão”, “Lê as minhas notas”\n" +
                    "• Lembretes: “Lembra-me daqui a 20 minutos de ligar à mãe”, " +
                    "“Lembra-me às 18:00 de levantar a encomenda”. A $nomeIA diz o lembrete em voz alta.\n" +
                    "• Alarmes: “Põe um alarme às 7 e meia”\n" +
                    "• Temporizadores: “Temporizador de 10 minutos”\n" +
                    "• Gerir: “Que alarmes tenho?”, “Cancela os alarmes”\n" +
                    "• Conversa livre: tudo o resto vai para a IA.",
                14f
            )
        )

        col.addView(secao("Comandos sobre a voz"))
        col.addView(
            texto(
                "• “descarregar voz”: descarrega a voz própria (Whisper).\n" +
                    "• “usar voz própria” e “usar voz do sistema”\n" +
                    "• “estado da voz”",
                14f
            )
        )

        col.addView(secao("Modelos de IA"))
        col.addView(
            texto(
                "• Rápido (0,5B): mais leve e veloz, respostas mais simples.\n" +
                    "• Melhor (1,5B): escreve melhor, mas é bem mais lento e pesa na memória.\n" +
                    "Em ⚙ Configurações vês a velocidade medida no teu telemóvel e podes trocar de modelo.",
                14f
            )
        )

        col.addView(secao("Voz: ouvidos e boca"))
        col.addView(
            texto(
                "• Ouvidos: a voz do sistema (Android/Google/Samsung), que depende do telemóvel, ou a voz própria " +
                    "(Whisper), que é independente e 100% offline. Base é mais rápida; Small é mais precisa.\n" +
                    "• Boca: a voz de resposta vem do telemóvel. Se faltar o português, instala os dados de voz em " +
                    "⚙ Configurações → Voz.",
                14f
            )
        )

        col.addView(secao("Privacidade"))
        col.addView(
            texto(
                "• Conversas, notas e lembretes ficam no telemóvel.\n" +
                    "• A internet só é usada para descarregar modelos, quando pedes.\n" +
                    "• A voz do sistema é fornecida pelo Android e pode usar a rede. A voz própria não usa.",
                14f
            )
        )

        col.addView(secao("Ainda não faz (em desenvolvimento)"))
        col.addView(
            texto(
                "• Ligar, enviar mensagens, tocar música e GPS.\n" +
                    "• Modo online com interruptor.\n" +
                    "• Personagem personalizável (aparência e voz).\n" +
                    "• Chamar por “Ei ${nomeIA}” sem tocar em nada.",
                14f
            )
        )

        col.addView(secao("Bom saber"))
        col.addView(
            texto(
                "• O modelo pequeno pode errar ou misturar palavras. O Melhor é mais capaz, mas mais lento.\n" +
                    "• Alarmes e lembretes vivem na app e não aparecem na app Relógio.\n" +
                    "• Enquanto a $nomeIA está ativa, o modelo fica na memória. ⏹ Parar liberta-a.\n" +
                    "• Em algumas marcas é preciso permitir a app “sem restrições de bateria”.\n" +
                    "• Os menus e comandos estão por agora só em português.",
                14f
            )
        )

        col.addView(botao("Voltar") { mostrarChat() })

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
        tela = "chat"
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
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(
                botao("⚙ Configurações") { mostrarConfig(false) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                botao("ℹ Ajuda") { mostrarInfo() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
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
        root.addView(barra, LinearLayout.LayoutParams(match, wrap))
        root.addView(conversaBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(acordarBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(downloadBtn, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(row, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)

        iniciar()
    }

    // ---------- Estado vindo do serviço ----------
    private fun atualizarUi() {
        if (tela != "chat" || !::chat.isInitialized) return
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

    // ---------- Download do modelo de IA ----------
    private fun textoBotao(): String {
        val mb = if (parcial().exists()) parcial().length() / 1_000_000 else 0L
        return if (mb > 0) "Continuar de onde parou" else "Descarregar IA (~${modeloAtual.mb} MB)"
    }

    private val poll = object : Runnable {
        override fun run() {
            if (tela != "chat") return
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
        if (tela != "chat" || !::status.isInitialized) return
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
    override fun onBackPressed() {
        if (tela == "info") {
            mostrarChat()
        } else if (tela == "config" && prefs.getBoolean("configurado", false)) {
            mostrarChat()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        AssistenteService.ouvinte = null
        super.onDestroy()
    }
}
